package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecache
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
@TestProfile(AanmeldMockProfile::class)
class AanmeldResourceTest {

    private val validator = OpenApiValidationFilter(
        OpenApiInteractionValidator
            .createForSpecificationUrl("openapi/berichtenuitvraag-api.yaml")
            .withLevelResolver(
                LevelResolver.create()
                    .withLevel("validation.response.body.schema.format.uri", ValidationReport.Level.WARN)
                    .build(),
            )
            .build(),
    )

    private val cloudEventsJson = "application/cloudevents+json"
    private val afzender = "00000001003214345000"

    @Inject
    lateinit var sessiecache: MockSessiecache

    @Inject
    lateinit var dedup: MockAanmeldDeduplicatie

    @BeforeEach
    fun reset() {
        sessiecache.reset()
        dedup.reset()
    }

    private fun event(
        id: String = "evt-${UUID.randomUUID()}",
        berichtId: UUID = UUID.randomUUID(),
        afzenderOin: String = afzender,
        specversion: String = "1.0",
        type: String = "nl.rijksoverheid.fbs.bericht.gepubliceerd",
        ontvangerType: String = "BSN",
        ontvangerWaarde: String = "999990019",
    ): String = """
        {
          "id": "$id",
          "source": "urn:nld:oin:$afzenderOin:systeem:fbs-magazijn",
          "specversion": "$specversion",
          "type": "$type",
          "subject": "$berichtId",
          "time": "2026-06-04T10:00:00Z",
          "datacontenttype": "application/json",
          "dataschema": "https://schemas.fbs.rijksoverheid.nl/bericht-gepubliceerd/v1",
          "data": {
            "berichtId": "$berichtId",
            "afzender": "$afzenderOin",
            "ontvanger": { "type": "$ontvangerType", "waarde": "$ontvangerWaarde" },
            "onderwerp": "Onderwerp",
            "inhoud": "Inhoud",
            "tijdstipOntvangst": "2026-06-04T09:59:00Z",
            "publicatietijdstip": "2026-06-04T10:00:00Z"
          }
        }
    """.trimIndent()

    @Test
    fun `geldig event schrijft bericht in de cache en geeft 202`() {
        val berichtId = UUID.randomUUID()

        given()
            .filter(validator)
            .contentType(cloudEventsJson)
            .body(event(berichtId = berichtId))
            .`when`().post("/api/v1/aanmeldingen")
            .then()
            .statusCode(202)

        val bericht = sessiecache.berichten[berichtId]
        // 1:1 OIN↔magazijn: het magazijn-id is de afzender-OIN zelf.
        assertEquals(afzender, bericht?.magazijnId)
        assertEquals(0, bericht?.aantalBijlagen)
        assertEquals(afzender, bericht?.afzender)
        assertEquals("999990019", bericht?.ontvanger)
    }

    @Test
    fun `geen actieve sessie geeft 202 maar schrijft niets`() {
        sessiecache.schrijfFouten.add(WebApplicationException("geen sessie", Response.Status.NOT_FOUND))

        given()
            .contentType(cloudEventsJson)
            .body(event())
            .`when`().post("/api/v1/aanmeldingen")
            .then()
            .statusCode(202)

        assertEquals(1, sessiecache.schrijfAanroepen.size)
        assertTrue(sessiecache.berichten.isEmpty())
    }

    @Test
    fun `duplicaat event wordt maar een keer verwerkt`() {
        val id = "evt-dup-1"

        repeat(2) {
            given()
                .contentType(cloudEventsJson)
                .body(event(id = id, berichtId = UUID.randomUUID()))
                .`when`().post("/api/v1/aanmeldingen")
                .then()
                .statusCode(202)
        }

        assertEquals(1, sessiecache.schrijfAanroepen.size)
    }

    @Test
    fun `onbekende afzender-OIN geeft 400 (contract-gevalideerd)`() {
        given()
            .filter(validator)
            .contentType(cloudEventsJson)
            .body(event(afzenderOin = "99999999999999999999"))
            .`when`().post("/api/v1/aanmeldingen")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", equalTo(400))

        assertTrue(sessiecache.schrijfAanroepen.isEmpty())
    }

    @Test
    fun `dedup-store onbereikbaar geeft 503 (contract-gevalideerd)`() {
        dedup.fout = WebApplicationException("redis down", 503)

        given()
            .filter(validator)
            .contentType(cloudEventsJson)
            .body(event())
            .`when`().post("/api/v1/aanmeldingen")
            .then()
            .statusCode(503)
            .contentType("application/problem+json")

        assertTrue(sessiecache.schrijfAanroepen.isEmpty())
    }

    @Test
    fun `geen-sessie-event wordt na sessie-opening alsnog geschreven bij herlevering`() {
        val id = "evt-herlevering"
        val berichtId = UUID.randomUUID()

        // Eerste levering: geen actieve sessie → 202, niets geschreven, marker vrijgegeven.
        sessiecache.schrijfFouten.add(WebApplicationException("geen sessie", Response.Status.NOT_FOUND))
        given()
            .contentType(cloudEventsJson)
            .body(event(id = id, berichtId = berichtId))
            .`when`().post("/api/v1/aanmeldingen")
            .then()
            .statusCode(202)

        // Tweede levering van hetzelfde event (sessie nu actief): omdat de marker is
        // vrijgegeven wordt het niet als duplicaat overgeslagen, maar alsnog geschreven.
        given()
            .contentType(cloudEventsJson)
            .body(event(id = id, berichtId = berichtId))
            .`when`().post("/api/v1/aanmeldingen")
            .then()
            .statusCode(202)

        assertEquals(2, sessiecache.schrijfAanroepen.size)
        assertEquals(afzender, sessiecache.berichten[berichtId]?.magazijnId)
    }

    @Test
    fun `onbekend extra attribuut en ontbrekend optioneel veld geven 202`() {
        val berichtId = UUID.randomUUID()
        val body = """
            {
              "id": "evt-extra",
              "source": "urn:nld:oin:$afzender:systeem:fbs-magazijn",
              "specversion": "1.0",
              "type": "nl.rijksoverheid.fbs.bericht.gepubliceerd",
              "subject": "$berichtId",
              "sequence": "42",
              "data": {
                "berichtId": "$berichtId",
                "afzender": "$afzender",
                "ontvanger": { "type": "BSN", "waarde": "999990019" },
                "onderwerp": "Onderwerp",
                "inhoud": "Inhoud",
                "publicatietijdstip": "2026-06-04T10:00:00Z"
              }
            }
        """.trimIndent()

        given()
            .contentType(cloudEventsJson)
            .body(body)
            .`when`().post("/api/v1/aanmeldingen")
            .then()
            .statusCode(202)

        assertEquals(1, sessiecache.berichten.size)
        assertEquals(berichtId, sessiecache.berichten[berichtId]?.berichtId)
    }

    @Test
    fun `verkeerde specversion geeft 400`() {
        given()
            .contentType(cloudEventsJson)
            .body(event(specversion = "0.3"))
            .`when`().post("/api/v1/aanmeldingen")
            .then()
            .statusCode(400)
    }

    @Test
    fun `onverwacht event-type geeft 400`() {
        given()
            .contentType(cloudEventsJson)
            .body(event(type = "nl.rijksoverheid.fbs.bericht.verwijderd"))
            .`when`().post("/api/v1/aanmeldingen")
            .then()
            .statusCode(400)
    }

    @Test
    fun `ongeldige ontvanger geeft 400`() {
        given()
            .contentType(cloudEventsJson)
            .body(event(ontvangerWaarde = "123456789"))
            .`when`().post("/api/v1/aanmeldingen")
            .then()
            .statusCode(400)
    }
}
