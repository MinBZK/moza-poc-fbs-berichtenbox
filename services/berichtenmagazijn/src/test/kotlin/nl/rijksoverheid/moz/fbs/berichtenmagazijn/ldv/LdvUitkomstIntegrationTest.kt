package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv

import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import javax.sql.DataSource
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever.BerichtOpslagService
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever.BijlageInvoer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieClaimVerwerker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Borgt tegen een echte PostgreSQL dat een verwerking die mislukt ná haar bevestigde
 * logregel in het logboek herkenbaar is als mislukt: een ERROR-child onder de oorspronkelijke
 * logregel, met dezelfde betrokkene en verwerkingsactiviteit. Leesregel: een logregel
 * zonder ERROR-child is geslaagd.
 *
 * De mock-tests borgen de volgorde van aanroepen; deze test borgt wat er daadwerkelijk in de
 * tabel komt — de parent-koppeling, het uitkomst-attribuut en dat er geen persoonsgegevens
 * in de foutattributen landen.
 *
 * Elke test gebruikt een eigen ontvanger, zodat zijn rijen te onderscheiden zijn van die van
 * andere tests in dit profiel, en ruimt ze na afloop op.
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
    fun ruimLogregelsOp() {
        val bsn = ontvanger ?: return

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "DELETE FROM logboek_dataverwerkingen WHERE attributes->>'dpl.core.data_subject_id' = ?",
            ).use { statement ->
                statement.setString(1, bsn)
                statement.executeUpdate()
            }
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
        assertFalse(uitkomst.foutMessage.orEmpty().contains("uitkering"), "berichtinhoud hoort niet in het logboek")
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
        leverAan(bsn).then().statusCode(201)

        // De test-downstream wijst naar een gesloten poort: elke poging is een NetwerkFout.
        // De scheduler staat in tests uit; verwerk hier de claims tot er niets meer klaarstaat.
        var verwerkt = 0

        while (verwerker.verwerkEenClaim()) verwerkt++

        assertTrue(verwerkt > 0, "de claim van deze aanlevering moet verwerkt zijn")

        val rijen = logregels(bsn, "publicatie-default")
        val pogingen = rijen.filter { it.uitkomst == null }
        assertTrue(pogingen.isNotEmpty(), "er moet minstens één leverpoging gelogd zijn")
        assertEquals(
            (1..pogingen.size).toList(),
            pogingen.map { it.poging }.sortedBy { it },
            "elke poging draagt haar eigen volgnummer",
        )

        pogingen.forEach { poging ->
            assertEquals("UNSET", poging.status)
            val uitkomst = rijen.single { it.parentSpanId == poging.spanId && it.uitkomst != null }
            assertUitkomstVan(poging, uitkomst)
            assertEquals("NetwerkFout", uitkomst.foutMessage)
        }

        assertEquals(pogingen.size * 2, rijen.size, "per poging precies één uitkomst — was: $rijen")
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

    private data class Rij(
        val traceId: String,
        val spanId: String,
        val parentSpanId: String?,
        val status: String,
        val activiteit: String?,
        val subjectType: String?,
        val uitkomst: String?,
        val foutMessage: String?,
        val poging: Int?,
    )

    private fun logregels(bsn: String, naam: String): List<Rij> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT trace_id, span_id, parent_span_id, status,
                   attributes->>'dpl.core.processing_activity_id' AS activiteit,
                   attributes->>'dpl.core.data_subject_id_type' AS subject_type,
                   attributes->>'${ProcessingHandler.OUTCOME_ATTRIBUTE_KEY}' AS uitkomst,
                   attributes->>'exception.message' AS fout_message,
                   attributes->>'publicatie.poging' AS poging
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
                                poging = resultaat.getString("poging")?.toInt(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        // Geldige BSN's (elfproef), alleen in deze test gebruikt.
        const val ONTVANGER_AANLEVEREN = "111222333"
        const val ONTVANGER_GESLAAGD = "123456782"
        const val ONTVANGER_PUBLICEREN = "100000009"
    }
}
