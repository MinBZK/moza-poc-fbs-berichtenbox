package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.WireMockMagazijnResource
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.WireMockProfielServiceResource
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.WireMockProfielServiceTestProfile
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * E2E-tests voor BerichtenOphalenResource met echte ProfielMagazijnResolver via WireMock.
 *
 * Gebruikt [WireMockProfielServiceTestProfile] zodat de echte REST-client naar WireMock
 * gaat in plaats van de in-memory [nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MockProfielServiceClient].
 */
@QuarkusTest
@TestProfile(WireMockProfielServiceTestProfile::class)
@QuarkusTestResource(WireMockProfielServiceResource::class)
@QuarkusTestResource(WireMockMagazijnResource::class)
class BerichtenOphalenResolverE2ETest {

    // Valideert response-body (inclusief Problem-schema) tegen de OpenAPI spec.
    private val responseOnlyFilter = OpenApiValidationFilter(
        OpenApiInteractionValidator
            .createForSpecificationUrl("openapi/berichtensessiecache-api.yaml")
            .withLevelResolver(
                LevelResolver.create()
                    .withLevel("validation.response.body.schema.type", ValidationReport.Level.WARN)
                    .withLevel("validation.request.parameter.header.missing", ValidationReport.Level.IGNORE)
                    .withLevel("validation.request.parameter.query.missing", ValidationReport.Level.IGNORE)
                    .withLevel("validation.request.body.missing", ValidationReport.Level.IGNORE)
                    .build(),
            )
            .build()
    )

    private val profielWireMock get() = WireMockProfielServiceResource.server!!
    private val magazijnA get() = WireMockMagazijnResource.serverA!!
    private val magazijnB get() = WireMockMagazijnResource.serverB!!

    @BeforeEach
    fun resetStubs() {
        profielWireMock.resetAll()
        magazijnA.resetAll()
        magazijnB.resetAll()

        // Default: magazijnen antwoorden met lege berichten-lijst
        val legeResponse = """{"berichten": []}"""
        magazijnA.stubFor(
            get(urlPathMatching("/.*")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(legeResponse),
            ),
        )
        magazijnB.stubFor(
            get(urlPathMatching("/.*")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(legeResponse),
            ),
        )
    }

    @Test
    fun `BSN met lege Profiel-voorkeuren geeft OPHALEN_GEREED met 0 berichten`() {
        profielWireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999996915")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"voorkeuren": []}"""),
            ),
        )

        val response = given()
            .header("X-Ontvanger", "BSN:999996915")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"event\":\"ophalen-gereed\""), "Verwacht ophalen-gereed in: $response")
        assertTrue(response.contains("\"totaalBerichten\":0"), "Verwacht totaalBerichten:0 in: $response")
        assertTrue(response.contains("\"totaalMagazijnen\":0"), "Verwacht totaalMagazijnen:0 in: $response")
    }

    @Test
    fun `BSN met Profiel-500 retourneert 503 met Retry-After`() {
        profielWireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999991401")).willReturn(
                aResponse().withStatus(500),
            ),
        )

        given()
            .header("X-Ontvanger", "BSN:999991401")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(503)
            .header("Retry-After", "30")
            .contentType(containsString("application/problem+json"))
            .body("type", containsString("profiel-service-onbereikbaar"))
    }

    @Test
    fun `X-Ontvanger zonder type-prefix retourneert 400`() {
        given()
            .header("X-Ontvanger", "999993653")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(400)
            .body("status", `is`(400))
    }

    @Test
    fun `OIN-ontvanger bevraagt alle magazijnen zonder Profiel-call`() {
        profielWireMock.stubFor(
            get(urlPathMatching("/.*")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"voorkeuren": []}"""),
            ),
        )

        val response = given()
            .header("X-Ontvanger", "OIN:00000001003214345000")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"event\":\"ophalen-gereed\""), "Verwacht ophalen-gereed in: $response")
        profielWireMock.verify(0, getRequestedFor(urlEqualTo("/api/profielservice/v1/OIN/00000001003214345000")))
    }

    // ── E8: OpenAPI contract — 503-response matcht Problem-schema ─────────

    @Test
    fun `503 op berichten ophalen matcht Problem-schema conform spec`() {
        profielWireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999991401")).willReturn(
                aResponse().withStatus(500),
            ),
        )

        given()
            .filter(responseOnlyFilter)
            .header("X-Ontvanger", "BSN:999991401")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(503)
    }

    // ── A: Cache-overwrite bij lege resolver ──────────────────────────────

    @Test
    fun `lege resolver overschrijft bestaande cache met lege lijst`() {
        // Eerste ophaal: opted-in voorkeur → magazijnen worden bevraagd en berichten gecached.
        profielWireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "voorkeuren": [
                            { "voorkeurType": "OntvangViaBerichtenbox", "waarde": "true",
                              "scopes": [ { "partij": { "identificatieType": "OIN",
                                                         "identificatieNummer": "00000001003214345000" } } ] }
                          ]
                        }
                        """.trimIndent(),
                    ),
            ),
        )

        val eersteOphaal = given()
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(eersteOphaal.contains("\"event\":\"ophalen-gereed\""), "Verwacht ophalen-gereed na eerste ophaal: $eersteOphaal")

        // Re-stub: lege voorkeuren → resolver retourneert geen magazijnen.
        profielWireMock.resetAll()
        profielWireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"voorkeuren": []}"""),
            ),
        )

        val tweedeOphaal = given()
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(tweedeOphaal.contains("\"event\":\"ophalen-gereed\""), "Verwacht ophalen-gereed na tweede ophaal: $tweedeOphaal")
        assertTrue(tweedeOphaal.contains("\"totaalBerichten\":0"), "Verwacht totaalBerichten:0 na lege voorkeur: $tweedeOphaal")
        assertTrue(tweedeOphaal.contains("\"totaalMagazijnen\":0"), "Verwacht totaalMagazijnen:0 na lege voorkeur: $tweedeOphaal")

        // Verifieer dat GET /berichten geen stale berichten toont.
        given()
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("totalElements", `is`(0))
    }

    // ── B: Lock-recovery na Profiel-fout ─────────────────────────────────

    @Test
    fun `Profiel-fout-503 release lock zodat retry mogelijk is`() {
        // Eerste ophaal: Profiel-500 → 503 terug naar client, lock moet vrijgegeven worden.
        profielWireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999991401")).willReturn(
                aResponse().withStatus(500),
            ),
        )

        given()
            .header("X-Ontvanger", "BSN:999991401")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(503)

        // Re-stub: nu wél een geldige voorkeur.
        profielWireMock.resetAll()
        profielWireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999991401")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"voorkeuren": []}"""),
            ),
        )

        // Tweede ophaal voor dezelfde ontvanger mag geen 409 geven; lock is vrijgegeven.
        val tweedeOphaal = given()
            .header("X-Ontvanger", "BSN:999991401")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(
            tweedeOphaal.contains("\"event\":\"ophalen-gereed\""),
            "Verwacht ophalen-gereed na lock-recovery: $tweedeOphaal",
        )
    }

    // ── E: Malformed X-Ontvanger varianten ───────────────────────────────

    @Test
    fun `X-Ontvanger met onbekend type-prefix retourneert 400 zonder echo van attacker-input`() {
        given()
            .header("X-Ontvanger", "FOO:123")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(400)
            .contentType(containsString("application/problem+json"))
            .body("status", `is`(400))
            // Attacker-controlled prefix mag niet in de detail terugkomen (geen echo).
            .body("detail", not(containsString("FOO")))
    }

    @Test
    fun `X-Ontvanger BSN met lege waarde retourneert 400`() {
        given()
            .header("X-Ontvanger", "BSN:")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(400)
            .contentType(containsString("application/problem+json"))
            .body("status", `is`(400))
    }

    @Test
    fun `X-Ontvanger BSN die elfproef faalt retourneert 400`() {
        // BSN 999993654 is een ongeldige BSN (elfproef mislukt); 999993653 is geldig.
        given()
            .header("X-Ontvanger", "BSN:999993654")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(400)
            .contentType(containsString("application/problem+json"))
            .body("status", `is`(400))
    }
}
