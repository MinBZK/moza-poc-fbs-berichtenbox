package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv

import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekWriteException
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever.BerichtOpslagService
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever.BijlageInvoer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.LeveringMislukt
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieClaimVerwerker
import nl.rijksoverheid.moz.fbs.common.LdvFoutSamenvatting
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Borgt tegen een echte PostgreSQL hoe de uitkomst van een verwerking in het logboek landt.
 * Een verwerking die zeker mislukt ná haar bevestigde logregel krijgt een ERROR-child onder
 * die logregel, met dezelfde betrokkene en verwerkingsactiviteit. Leesregel: een logregel op
 * `UNSET` zonder ERROR-child is geslaagd.
 *
 * De mock-tests borgen de volgorde van aanroepen; deze test borgt wat er daadwerkelijk in de
 * tabel komt, en wat er gebeurt als het logboek een schrijfactie weigert.
 *
 * De test-downstream wijst naar een gesloten poort: elke leverpoging is een geweigerde
 * verbinding, dus zeker niet verzonden. De scheduler staat in tests uit; de tests verwerken
 * hun eigen claim stap voor stap, na de openstaande claims van andere tests te hebben
 * afgesloten — anders bepaalt de backoff-timing welke claim een stap oppakt.
 * Elke test gebruikt een eigen ontvanger en ruimt zijn rijen na afloop op.
 */
@QuarkusTest
@TestProfile(LdvPostgresIntegrationTest.LdvAanProfile::class)
class LdvUitkomstIntegrationTest {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var verwerker: PublicatieClaimVerwerker

    private var ontvanger: String? = null

    @AfterEach
    fun ruimOp() {
        voerUit("DROP TRIGGER IF EXISTS $WEIGER_TRIGGER ON logboek_dataverwerkingen")
        voerUit("DROP TRIGGER IF EXISTS $WEIGER_TRIGGER ON publicatie_deliveries")
        ontvanger?.let { bsn ->
            voerUit("DELETE FROM logboek_dataverwerkingen WHERE attributes->>'dpl.core.data_subject_id' = '$bsn'")
        }
    }

    @Test
    fun `een opslagfout na de bevestigde logregel staat als ERROR-child in het logboek`() {
        val bsn = gebruik(ONTVANGER_AANLEVEREN)
        installeerFalendeOpslag("Failing row contains (1, $bsn, Beste heer, uw uitkering is gewijzigd)")

        leverAan(bsn).then().statusCode(500)

        val rijen = logregels(bsn, "aanleveren-bericht")
        assertEquals(2, rijen.size, "één logregel vooraf en één uitkomst-logregel — was: $rijen")

        val logregel = rijen.single { it.uitkomst == null }
        val uitkomst = rijen.single { it.uitkomst != null }
        assertEquals("UNSET", logregel.status, "de logregel vooraf blijft staan zoals hij bevestigd is")
        assertUitkomstVan(logregel, uitkomst)
        assertEquals("java.lang.IllegalStateException", uitkomst.foutMessage)
        assertEquals(LdvFoutSamenvatting::class.java.name, uitkomst.foutType)
        assertGeenPersoonsgegevensBuitenBetrokkene(bsn, "uitkering", "Failing row")
    }

    @Test
    fun `een echte rollback bij de opslag staat als ERROR-child in het logboek, zonder bericht`() {
        // Zonder QuarkusMock: de transactie, circuit breaker en exception mappers lopen mee,
        // en de database weigert de outbox-rij pas bij de commit.
        val bsn = gebruik(ONTVANGER_ROLLBACK)
        weigerInserts("publicatie_deliveries", voorwaarde = "true")

        leverAan(bsn).then().statusCode(greaterThanOrEqualTo(500))

        assertEquals(0, aantalBerichtenVoor(bsn), "de transactie hoort teruggedraaid te zijn")
        val rijen = logregels(bsn, "aanleveren-bericht")
        val logregel = rijen.single { it.uitkomst == null }
        val uitkomst = rijen.single { it.uitkomst != null }
        assertUitkomstVan(logregel, uitkomst)
        assertEquals(LdvFoutSamenvatting::class.java.name, uitkomst.foutType)
        assertGeenPersoonsgegevensBuitenBetrokkene(bsn, "Logboek weigert")
    }

    @Test
    fun `een geslaagde aanlevering schrijft alleen de logregel vooraf`() {
        val bsn = gebruik(ONTVANGER_GESLAAGD)

        leverAan(bsn).then().statusCode(201)

        val rijen = logregels(bsn, "aanleveren-bericht")
        assertEquals(1, rijen.size, "geen uitkomst-logregel bij succes — was: $rijen")
        assertNull(rijen.single().uitkomst)
    }

    @Test
    fun `elke mislukte leverpoging staat met haar volgnummer en een ERROR-child in het logboek`() {
        val bsn = gebruik(ONTVANGER_PUBLICEREN)
        sluitAndereClaimsAf()
        val berichtId = leverAanEnGeefBerichtId(bsn)

        // max-pogingen=3 in de testconfig: de derde poging maakt de claim definitief MISLUKT.
        repeat(MAX_POGINGEN) { verwerkPoging() }

        assertEquals("MISLUKT" to MAX_POGINGEN, claimVan(berichtId))
        assertFalse(verwerker.verwerkEenClaim(), "na de laatste poging staat er niets meer open")
        val rijen = logregels(bsn, "publicatie-default")
        val pogingen = rijen.filter { it.uitkomst == null }
        assertEquals((1..MAX_POGINGEN).toList(), pogingen.map { it.poging }.sortedBy { it })

        pogingen.forEach { poging ->
            assertEquals("UNSET", poging.status)
            assertEquals(berichtId, poging.berichtId, "pogingen voor één verstrekking delen het bericht")
            val uitkomst = rijen.single { it.parentSpanId == poging.spanId && it.uitkomst != null }
            assertUitkomstVan(poging, uitkomst)
            assertEquals("NetwerkFout", uitkomst.foutMessage)
            assertEquals(LeveringMislukt::class.java.name, uitkomst.foutType)
        }

        assertGeenPersoonsgegevensBuitenBetrokkene(bsn, "127.0.0.1", "Connection refused")

        assertEquals(MAX_POGINGEN * 2, rijen.size, "per poging precies één uitkomst — was: $rijen")
    }

    @Test
    fun `een geweigerde logregel houdt de levering tegen en laat de claim openstaan`() {
        val bsn = gebruik(ONTVANGER_FAIL_CLOSED)
        sluitAndereClaimsAf()
        val berichtId = leverAanEnGeefBerichtId(bsn)

        weigerInserts("logboek_dataverwerkingen", voorwaarde = "true")

        assertThrows<LogboekWriteException> { verwerker.verwerkEenClaim() }

        assertEquals("TE_PUBLICEREN" to 0, claimVan(berichtId), "zonder logregel geen poging en geen levering")
        assertTrue(logregels(bsn, "publicatie-default").isEmpty())
    }

    @Test
    fun `een verloren uitkomst-logregel laat de claim-afhandeling en de volgende poging ongemoeid`() {
        val bsn = gebruik(ONTVANGER_VERLOREN_UITKOMST)
        sluitAndereClaimsAf()
        val berichtId = leverAanEnGeefBerichtId(bsn)

        weigerInserts(
            "logboek_dataverwerkingen",
            voorwaarde = "NEW.attributes->>'${ProcessingHandler.OUTCOME_ATTRIBUTE_KEY}' IS NOT NULL",
        )
        val meldingen = vangMeldingen { verwerkPoging() }

        assertEquals("TE_PUBLICEREN" to 1, claimVan(berichtId), "de poging telt, de retry staat gepland")
        val eerstePoging = logregels(bsn, "publicatie-default").single()
        assertNull(eerstePoging.uitkomst, "de uitkomst-logregel is geweigerd")
        // Zonder deze melding is "geweigerd" niet te onderscheiden van "nooit geprobeerd".
        assertTrue(
            meldingen.any { it.startsWith(MislukteUitkomst.ALERT_TOKEN) && it.contains(eerstePoging.spanId) },
            "verwacht ${MislukteUitkomst.ALERT_TOKEN} met de span_id van de poging — was: $meldingen",
        )

        // Blijft er een schrijffout op de thread achter, dan faalt de volgende poging fail-closed.
        voerUit("DROP TRIGGER $WEIGER_TRIGGER ON logboek_dataverwerkingen")
        verwerkPoging()

        assertEquals("TE_PUBLICEREN" to 2, claimVan(berichtId))
        val tweedePoging = logregels(bsn, "publicatie-default").single { it.poging == 2 }
        assertTrue(logregels(bsn, "publicatie-default").any { it.parentSpanId == tweedePoging.spanId })
    }

    private fun gebruik(bsn: String): String {
        ontvanger = bsn
        return bsn
    }

    private fun assertUitkomstVan(logregel: Rij, uitkomst: Rij) {
        assertEquals(ProcessingHandler.OUTCOME_FAILED, uitkomst.uitkomst)
        assertEquals("ERROR", uitkomst.status)
        assertEquals(logregel.traceId, uitkomst.traceId, "de uitkomst hoort in dezelfde trace")
        assertEquals(logregel.spanId, uitkomst.parentSpanId, "de uitkomst hangt onder de logregel vooraf")
        assertEquals(logregel.activiteit, uitkomst.activiteit)
        assertEquals("BSN", uitkomst.subjectType)
    }

    private fun installeerFalendeOpslag(melding: String) {
        val falendeOpslag = object : BerichtOpslagService(
            repository = mockk(relaxed = true),
            bijlageRepository = mockk(relaxed = true),
            validatieService = mockk(relaxed = true),
            publicatieOutbox = mockk(relaxed = true),
            clock = java.time.Clock.systemUTC(),
        ) {
            override fun slaBerichtOp(bericht: Bericht, bijlagen: List<BijlageInvoer>): Nothing =
                throw IllegalStateException(melding)
        }

        QuarkusMock.installMockForType(falendeOpslag, BerichtOpslagService::class.java)
    }

    /** Laat [tabel] elke insert weigeren die aan [voorwaarde] voldoet; [ruimOp] haalt dit weg. */
    private fun weigerInserts(tabel: String, voorwaarde: String) {
        voerUit(
            """
            CREATE OR REPLACE FUNCTION $WEIGER_TRIGGER() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'Logboek weigert de schrijfactie'; END $$
            """.trimIndent(),
        )
        voerUit(
            "CREATE TRIGGER $WEIGER_TRIGGER BEFORE INSERT ON $tabel " +
                "FOR EACH ROW WHEN ($voorwaarde) EXECUTE FUNCTION $WEIGER_TRIGGER()",
        )
    }

    private fun sluitAndereClaimsAf() {
        voerUit("UPDATE publicatie_deliveries SET status = 'MISLUKT' WHERE status = 'TE_PUBLICEREN'")
    }

    /** Eén poging voor de enige openstaande claim; de backoff ertussen wordt overgeslagen. */
    private fun verwerkPoging() {
        // Een minuut terug: de claim-query vergelijkt met de JVM-klok, en een database in een
        // VM kan voorlopen.
        voerUit(
            "UPDATE publicatie_deliveries SET volgende_poging = now() - interval '1 minute' " +
                "WHERE status = 'TE_PUBLICEREN'",
        )

        assertTrue(verwerker.verwerkEenClaim(), "er moet een claim klaarstaan")
    }

    private fun claimVan(berichtId: String): Pair<String, Int> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT d.status, d.pogingen
              FROM publicatie_deliveries d
              JOIN berichten b ON b.id = d.bericht_db_id
             WHERE b.bericht_id = CAST(? AS uuid)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, berichtId)

            statement.executeQuery().use { resultaat ->
                assertTrue(resultaat.next(), "claim voor bericht $berichtId ontbreekt")
                resultaat.getString("status") to resultaat.getInt("pogingen")
            }
        }
    }

    private fun voerUit(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    /**
     * Persoonsgegevens mogen alleen in de betrokkene-velden staan. Controleert de hele
     * attributen-JSON van elke rij van [bsn] op [verboden] fragmenten.
     */
    private fun assertGeenPersoonsgegevensBuitenBetrokkene(bsn: String, vararg verboden: String) {
        val rijen = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT (attributes - 'dpl.core.data_subject_id')::text AS attributen " +
                    "FROM logboek_dataverwerkingen WHERE attributes->>'dpl.core.data_subject_id' = ?",
            ).use { statement ->
                statement.setString(1, bsn)

                statement.executeQuery().use { resultaat ->
                    buildList { while (resultaat.next()) add(resultaat.getString("attributen")) }
                }
            }
        }

        assertTrue(rijen.isNotEmpty(), "geen rijen voor de betrokkene gevonden")

        rijen.forEach { attributen ->
            assertFalse(attributen.contains(bsn), "BSN buiten dpl.core.data_subject_id: $attributen")
            verboden.forEach { fragment ->
                assertFalse(attributen.contains(fragment), "'$fragment' hoort niet in het logboek: $attributen")
            }
        }
    }

    private fun aantalBerichtenVoor(bsn: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM berichten WHERE ontvanger_waarde = ?").use { statement ->
            statement.setString(1, bsn)

            statement.executeQuery().use { resultaat ->
                resultaat.next()
                resultaat.getInt(1)
            }
        }
    }

    /** De ERROR-meldingen van [MislukteUitkomst] tijdens [actie], geformatteerd. */
    private fun vangMeldingen(actie: () -> Unit): List<String> {
        val meldingen = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                // In Quarkus is de message een printf-patroon; de waarden staan in de parameters.
                meldingen.add((listOf(record.message) + record.parameters.orEmpty().map { it.toString() }).joinToString(" "))
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        val logger = Logger.getLogger(MislukteUitkomst::class.java.name)
        logger.addHandler(handler)

        try {
            actie()
        } finally {
            logger.removeHandler(handler)
        }

        return meldingen
    }

    private fun leverAan(bsn: String) = given()
        .contentType(ContentType.JSON)
        .body(
            """
            {
              "afzender": "00000001003214345000",
              "ontvanger": { "type": "BSN", "waarde": "$bsn" },
              "onderwerp": "LDV-uitkomsttest",
              "inhoud": "Inhoud"
            }
            """.trimIndent(),
        )
        .post("/api/v1/aanleveringen")

    private fun leverAanEnGeefBerichtId(bsn: String): String =
        leverAan(bsn).then().statusCode(201).extract().path("berichtId")

    private data class Rij(
        val traceId: String,
        val spanId: String,
        val parentSpanId: String?,
        val status: String,
        val activiteit: String?,
        val subjectType: String?,
        val uitkomst: String?,
        val foutMessage: String?,
        val foutType: String?,
        val poging: Int?,
        val berichtId: String?,
    )

    private fun logregels(bsn: String, naam: String): List<Rij> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT trace_id, span_id, parent_span_id, status,
                   attributes->>'dpl.core.processing_activity_id' AS activiteit,
                   attributes->>'dpl.core.data_subject_id_type' AS subject_type,
                   attributes->>'${ProcessingHandler.OUTCOME_ATTRIBUTE_KEY}' AS uitkomst,
                   attributes->>'exception.message' AS fout_message,
                   attributes->>'exception.type' AS fout_type,
                   attributes->>'publicatie.poging' AS poging,
                   attributes->>'publicatie.bericht_id' AS bericht_id
              FROM logboek_dataverwerkingen
             WHERE attributes->>'dpl.core.data_subject_id' = ? AND name = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, bsn)
            statement.setString(2, naam)

            statement.executeQuery().use { resultaat ->
                buildList {
                    while (resultaat.next()) {
                        add(
                            Rij(
                                traceId = resultaat.getString("trace_id"),
                                spanId = resultaat.getString("span_id"),
                                parentSpanId = resultaat.getString("parent_span_id"),
                                status = resultaat.getString("status"),
                                activiteit = resultaat.getString("activiteit"),
                                subjectType = resultaat.getString("subject_type"),
                                uitkomst = resultaat.getString("uitkomst"),
                                foutMessage = resultaat.getString("fout_message"),
                                foutType = resultaat.getString("fout_type"),
                                poging = resultaat.getString("poging")?.toInt(),
                                berichtId = resultaat.getString("bericht_id"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_POGINGEN = 3
        const val WEIGER_TRIGGER = "ldv_test_weiger"

        // Geldige BSN's (elfproef), alleen in deze test gebruikt.
        const val ONTVANGER_AANLEVEREN = "111222333"
        const val ONTVANGER_GESLAAGD = "123456782"
        const val ONTVANGER_PUBLICEREN = "100000009"
        const val ONTVANGER_FAIL_CLOSED = "100000010"
        const val ONTVANGER_VERLOREN_UITKOMST = "100000022"
        const val ONTVANGER_ROLLBACK = "100000034"
    }
}
