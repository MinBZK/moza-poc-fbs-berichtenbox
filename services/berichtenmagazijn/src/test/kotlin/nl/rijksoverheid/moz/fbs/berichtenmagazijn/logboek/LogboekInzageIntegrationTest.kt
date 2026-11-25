package nl.rijksoverheid.moz.fbs.berichtenmagazijn.logboek

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ProcessingActivities
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv.LdvPostgresIntegrationTest
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * Inzage tegen een echte PostgreSQL: één echte aanlevering en verwijdering, aangevuld met
 * rechtstreeks ingevoegde logregels voor de gevallen die in een test lastig uit te lokken zijn
 * (een ERROR-child onder een publicatie, een logregel op ERROR, een `BERICHT_ID_ONLY`-regel).
 */
@QuarkusTest
@TestProfile(LdvPostgresIntegrationTest.LdvAanProfile::class)
class LogboekInzageIntegrationTest {

    @Inject
    lateinit var dataSource: DataSource

    private val traceIds = mutableListOf<String>()

    @BeforeEach
    fun zorgVoorTabel() {
        // De wrapper maakt de tabel pas bij de eerste logregel aan; de inzage zelf is er een.
        inzage(ANDERE_BSN).then().statusCode(200)
        await().atMost(Duration.ofSeconds(5)).until { tabelBestaat() }
    }

    @AfterEach
    fun ruimOp() {
        voerUit(
            "DELETE FROM logboek_dataverwerkingen WHERE attributes->>'dpl.core.data_subject_id' IN (?, ?)",
            BSN,
            ANDERE_BSN,
        )
        traceIds.forEach { voerUit("DELETE FROM logboek_dataverwerkingen WHERE trace_id = ?", it) }
    }

    @Test
    fun `de inzage past de leesregel toe en toont alleen de verwerkingen van de betrokkene`() {
        val berichtId = leverAan(BSN)
        verwijder(berichtId, BSN)

        val publicatieTrace = voegIn(BSN, "BSN", "publicatie-default", "UNSET", 3_000, publicatie(berichtId))
        voegIn(
            BSN, "BSN", "publicatie-default", "ERROR", 3_001,
            mapOf("moza.ldv.uitkomst" to "mislukt", "exception.message" to "NetwerkFout"),
            traceId = publicatieTrace.first, parentSpanId = publicatieTrace.second,
        )
        voegIn(
            BSN, "BSN", "aanleveren-bericht", "ERROR", 2_000,
            mapOf("exception.type" to "nl.voorbeeld.Fout", "exception.message" to "Failing row contains $BSN"),
        )
        voegIn(
            berichtId, "BERICHT_ID_ONLY", "publicatie-default", "ERROR", 4_000,
            mapOf("publicatie.fout" to "bericht-niet-gevonden"),
        )
        voegIn(UUID.randomUUID().toString(), "BERICHT_ID_ONLY", "publicatie-default", "ERROR", 4_001, emptyMap())
        voegIn(ANDERE_BSN, "BSN", "aanleveren-bericht", "UNSET", 5_000, emptyMap())

        val antwoord = inzage(BSN).then().statusCode(200).extract().jsonPath()

        assertEquals(BSN, antwoord.getString("betrokkene.waarde"))
        val verwerkingen = antwoord.getList<Map<String, Any?>>("verwerkingen")
        assertEquals(
            listOf("verwijderen-bericht", "aanleveren-bericht", "publicatie-default", "publicatie-default", "aanleveren-bericht"),
            verwerkingen.map { it["handeling"] },
            "nieuwste eerst; geen ERROR-child als eigen regel, geen regels van een ander",
        )

        val (verwijdering, aanlevering, onbekendBericht, publicatie, mislukteAanlevering) = verwerkingen
        assertEquals("geslaagd", aanlevering["uitkomst"])
        assertTrue(aanlevering["verwerkingsactiviteit"] != null)
        assertEquals("geslaagd", verwijdering["uitkomst"])

        assertEquals("mislukt", onbekendBericht["uitkomst"])
        assertEquals(berichtId, onbekendBericht["berichtId"], "BERICHT_ID_ONLY-regel via het verwijderde bericht")
        assertEquals("bericht-niet-gevonden", onbekendBericht["foutcategorie"])

        assertEquals("mislukt", mislukteAanlevering["uitkomst"], "logregel op ERROR")
        assertEquals("nl.voorbeeld.Fout", mislukteAanlevering["foutcategorie"], "nooit de ruwe exception.message")

        assertEquals("mislukt", publicatie["uitkomst"], "UNSET met ERROR-child")
        assertEquals("NetwerkFout", publicatie["foutcategorie"])
        assertEquals("default", publicatie["verstrektAan"])
        assertEquals(berichtId, publicatie["berichtId"])
        assertEquals(1, publicatie["poging"])
        assertNull(aanlevering["foutcategorie"])
    }

    @Test
    fun `de inzage legt zichzelf vast in het logboek`() {
        inzage(BSN).then().statusCode(200)

        await().atMost(Duration.ofSeconds(5)).until {
            telRijen("inzage-logboek", BSN, ProcessingActivities.MAGAZIJN_INZAGE) == 1
        }
        val volgende = inzage(BSN).then().statusCode(200).extract().jsonPath()
        assertEquals(listOf("inzage-logboek"), volgende.getList<String>("verwerkingen.handeling"))
    }

    @Test
    fun `de periode beperkt de verwerkingen`() {
        voegIn(BSN, "BSN", "aanleveren-bericht", "UNSET", 1_000, emptyMap())
        voegIn(BSN, "BSN", "aanleveren-bericht", "UNSET", 9_000, emptyMap())

        val antwoord: JsonPath = given()
            .header("X-Betrokkene", "BSN:$BSN")
            .queryParam("van", "1970-01-01T00:00:05Z")
            .queryParam("tot", "1970-01-01T00:00:10Z")
            .get("/api/v1/logboek/verwerkingen")
            .then().statusCode(200).extract().jsonPath()

        assertEquals(listOf("1970-01-01T00:00:09Z"), antwoord.getList<String>("verwerkingen.tijdstip"))
    }

    @Test
    fun `een ongeldige betrokkene geeft 400`() {
        given().header("X-Betrokkene", "BSN:123456789").get("/api/v1/logboek/verwerkingen")
            .then().statusCode(400)
    }

    private fun publicatie(berichtId: String) = mapOf(
        "publicatie.bericht_id" to berichtId,
        "publicatie.doel" to "default",
        "publicatie.poging" to "1",
    )

    /** Voegt een logregel in en geeft trace- en span-id terug. */
    private fun voegIn(
        subjectId: String,
        subjectType: String,
        naam: String,
        status: String,
        startMillis: Long,
        attributen: Map<String, String>,
        traceId: String = UUID.randomUUID().toString().replace("-", ""),
        parentSpanId: String? = null,
    ): Pair<String, String> {
        val spanId = UUID.randomUUID().toString().take(16)
        traceIds += traceId
        val alle = attributen + mapOf(
            "dpl.core.data_subject_id" to subjectId,
            "dpl.core.data_subject_id_type" to subjectType,
        )
        val json = alle.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
        voerUit(
            "INSERT INTO logboek_dataverwerkingen (trace_id, span_id, status, \"name\", start_time, end_time, " +
                "parent_span_id, attributes, resource) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, '{}'::jsonb)",
            traceId, spanId, status, naam, startMillis, startMillis, parentSpanId, json,
        )

        return traceId to spanId
    }

    private fun telRijen(naam: String, bsn: String, activiteit: String): Int = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT count(*) FROM logboek_dataverwerkingen WHERE name = ? " +
                "AND attributes->>'dpl.core.data_subject_id' = ? AND attributes->>'dpl.core.processing_activity_id' = ?",
        ).use { s ->
            s.setString(1, naam)
            s.setString(2, bsn)
            s.setString(3, activiteit)
            s.executeQuery().use { r ->
                r.next()
                r.getInt(1)
            }
        }
    }

    private fun tabelBestaat(): Boolean = dataSource.connection.use { c ->
        c.prepareStatement("SELECT to_regclass('logboek_dataverwerkingen') IS NOT NULL").use { s ->
            s.executeQuery().use { r ->
                r.next()
                r.getBoolean(1)
            }
        }
    }

    private fun voerUit(sql: String, vararg parameters: Any?) {
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { s ->
                parameters.forEachIndexed { i, p -> s.setObject(i + 1, p) }
                s.executeUpdate()
            }
        }
    }

    private fun inzage(bsn: String) = given()
        .header("X-Betrokkene", "BSN:$bsn")
        .get("/api/v1/logboek/verwerkingen")

    private fun leverAan(bsn: String): String = given()
        .contentType(ContentType.JSON)
        .body(
            """
            {
              "afzender": "00000001003214345000",
              "ontvanger": { "type": "BSN", "waarde": "$bsn" },
              "onderwerp": "Inzagetest",
              "inhoud": "Inhoud"
            }
            """.trimIndent(),
        )
        .post("/api/v1/aanleveringen")
        .then().statusCode(201).extract().path("berichtId")

    private fun verwijder(berichtId: String, bsn: String) {
        given().header("X-Ontvanger", "BSN:$bsn").delete("/api/v1/berichten/$berichtId")
            .then().statusCode(204)
    }

    private companion object {
        const val BSN = "999900109"
        const val ANDERE_BSN = "999900110"
    }
}
