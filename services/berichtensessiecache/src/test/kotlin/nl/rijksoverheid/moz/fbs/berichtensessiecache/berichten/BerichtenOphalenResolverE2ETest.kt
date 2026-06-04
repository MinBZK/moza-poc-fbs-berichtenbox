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
        // Strikt geen Profiel-call op welk pad dan ook (exacte URL-match zou OIN-shortcut-removal missen).
        profielWireMock.verify(0, getRequestedFor(urlPathMatching("/api/profielservice/.*")))
    }

    // ── D: Defensief — malformed upstream-OIN wordt overgeslagen ──────────

    @Test
    fun `Profiel met enkel ongeldige OIN in scope emit OPHALEN_FOUT (geen silent empty)`() {
        // Profiel levert één scope met ongeldige OIN. 100% effective-empty → resolver
        // gooit configDrift → service emit OPHALEN_FOUT zodat client weet dat configuratie-
        // mismatch speelt i.p.v. legitiem "geen berichten".
        profielWireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999996915")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "voorkeuren": [
                            { "voorkeurType": "OntvangViaBerichtenbox", "waarde": "true",
                              "scopes": [ { "partij": { "identificatieType": "OIN",
                                                         "identificatieNummer": "12345" } } ] }
                          ]
                        }
                        """.trimIndent(),
                    ),
            ),
        )

        val response = given()
            .header("X-Ontvanger", "BSN:999996915")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"event\":\"ophalen-fout\""), "Verwacht ophalen-fout in: $response")
        assertTrue(response.contains("configuratie"), "Verwacht configuratie-mismatch melding in: $response")
        magazijnA.verify(0, getRequestedFor(urlPathMatching("/.*")))
        magazijnB.verify(0, getRequestedFor(urlPathMatching("/.*")))
    }

    // ── R: Routing — opted-in OIN filtert magazijn-bevraging ──────────────

    @Test
    fun `BSN met opt-in voor magazijn-a bevraagt alleen magazijn-a en niet magazijn-b`() {
        // Scope = afzender-OIN van magazijn-a (zie WireMockMagazijnResource).
        // De resolver mag alleen magazijn-a in de bevraging meenemen, niet magazijn-b.
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

        val response = given()
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(response.contains("\"event\":\"ophalen-gereed\""), "Verwacht ophalen-gereed in: $response")
        assertTrue(response.contains("\"totaalMagazijnen\":1"), "Verwacht totaalMagazijnen:1 in: $response")

        magazijnA.verify(1, getRequestedFor(urlPathMatching("/.*")))
        magazijnB.verify(0, getRequestedFor(urlPathMatching("/.*")))
    }

    // ── P: Partial failure — 1 magazijn OK, 1 magazijn FOUT ───────────────

    @Test
    fun `magazijn-a OK en magazijn-b 500 levert OPHALEN_GEREED met 1 geslaagd 1 mislukt en cachet alleen het overlevende magazijn`() {
        // BSN opted-in voor beide magazijnen (beide afzender-OINs in scope) zodat de
        // resolver magazijn-a én magazijn-b bevraagt.
        profielWireMock.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999993653")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "voorkeuren": [
                            { "voorkeurType": "OntvangViaBerichtenbox", "waarde": "true",
                              "scopes": [
                                { "partij": { "identificatieType": "OIN", "identificatieNummer": "00000001003214345000" } },
                                { "partij": { "identificatieType": "OIN", "identificatieNummer": "00000001823288444000" } }
                              ] }
                          ]
                        }
                        """.trimIndent(),
                    ),
            ),
        )

        // magazijn-a levert 1 bericht; magazijn-b faalt met HTTP 500.
        magazijnA.stubFor(
            get(urlPathMatching("/.*")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """
                    {
                      "berichten": [
                        {
                          "berichtId": "11111111-1111-1111-1111-111111111111",
                          "afzender": "00000001003214345000",
                          "ontvanger": { "type": "BSN", "waarde": "999993653" },
                          "onderwerp": "Bericht van magazijn-a",
                          "inhoud": "Inhoud van magazijn-a",
                          "publicatietijdstip": "2026-03-10T10:00:00Z",
                          "magazijnId": "magazijn-a",
                          "aantalBijlagen": 0
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
        )
        magazijnB.stubFor(
            get(urlPathMatching("/.*")).willReturn(aResponse().withStatus(500)),
        )

        val response = given()
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        // Degradatie i.p.v. totale fout: één magazijn faalt, het andere slaagt.
        assertTrue(response.contains("\"event\":\"ophalen-gereed\""), "Verwacht ophalen-gereed in: $response")
        assertTrue(response.contains("\"geslaagd\":1"), "Verwacht geslaagd:1 in: $response")
        assertTrue(response.contains("\"mislukt\":1"), "Verwacht mislukt:1 in: $response")
        assertTrue(response.contains("\"totaalMagazijnen\":2"), "Verwacht totaalMagazijnen:2 in: $response")
        assertTrue(response.contains("\"totaalBerichten\":1"), "Verwacht totaalBerichten:1 in: $response")

        // Het overlevende magazijn-a-bericht is gecached ondanks de magazijn-b-fout.
        given()
            .header("X-Ontvanger", "BSN:999993653")
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("totalElements", `is`(1))
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
