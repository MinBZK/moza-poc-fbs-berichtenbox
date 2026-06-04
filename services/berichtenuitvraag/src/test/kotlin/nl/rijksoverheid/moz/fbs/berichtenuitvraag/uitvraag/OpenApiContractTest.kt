package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Contract-tests: response-shapes per endpoint conform berichtenuitvraag-api.yaml.
 * Backends gemockt via WireMock (sessiecache + magazijn). Tolerantie op
 * `validation.response.body.schema.type` op WARN, gelijk aan de sessiecache-
 * suite — voorkomt false positives bij null-velden in HAL-_links.
 */
@QuarkusTest
@QuarkusTestResource(WireMockBackendsResource::class)
class OpenApiContractTest {

    private val validator = OpenApiValidationFilter(
        OpenApiInteractionValidator
            .createForSpecificationUrl("openapi/berichtenuitvraag-api.yaml")
            .withLevelResolver(
                LevelResolver.create()
                    .withLevel("validation.response.body.schema.type", ValidationReport.Level.WARN)
                    .build(),
            )
            .build(),
    )

    private val ontvanger = "BSN:999990019"

    @BeforeEach
    fun resetStubs() {
        WireMockBackendsResource.sessiecache?.resetAll()
        WireMockBackendsResource.magazijn?.resetAll()
        // Default-lookup voor multi-magazijn routering (BerichtBeheerService +
        // BerichtOphaalService.haalBijlage doen vóór elke write/download een
        // sessiecache.bericht()-lookup). Wildcard met lage prio; tests met een
        // expliciete `urlPathEqualTo`-stub voor een specifieke berichtId
        // overschrijven dit gedrag automatisch.
        WireMockBackendsResource.sessiecache!!.stubFor(
            com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching("/api/v1/berichten/[0-9a-fA-F-]{36}"),
            ).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"berichtId":"00000000-0000-0000-0000-000000000000","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"magazijn-a"}"""),
            ),
        )
    }

    @Test
    fun `GET berichten levert valide BerichtenLijst`() {
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"berichten":[]}"""),
                ),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(200)
    }

    @Test
    fun `GET berichten-zoeken levert valide BerichtenLijst`() {
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/_zoeken"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"berichten":[]}"""),
                ),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .queryParam("q", "rente")
            .`when`()
            .get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(200)
    }

    @Test
    fun `GET bericht by id levert valide Bericht`() {
        val id = UUID.randomUUID()
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"berichtId":"$id","onderwerp":"Test","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"magazijn-a"}"""),
                ),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten/$id")
            .then()
            .statusCode(200)
    }

    @Test
    fun `PATCH bericht doet dual-write en levert valide Bericht`() {
        val id = UUID.randomUUID()
        val body = """{"berichtId":"$id","onderwerp":"Test","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"magazijn-a"}"""
        // Magazijn-PATCH OK
        WireMockBackendsResource.magazijn!!.stubFor(
            patch(urlPathMatching("/api/v1/berichten/$id"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body),
                ),
        )
        // Sessiecache-PATCH OK
        WireMockBackendsResource.sessiecache!!.stubFor(
            patch(urlPathMatching("/api/v1/berichten/$id"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body),
                ),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(200)
    }

    @Test
    fun `DELETE bericht doet dual-write en geeft 204`() {
        val id = UUID.randomUUID()
        WireMockBackendsResource.magazijn!!.stubFor(
            delete(urlPathMatching("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            delete(urlPathMatching("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(204)
    }

    @Test
    fun `GET bijlage streamt bytes met juist Content-Type`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        // De service haalt eerst het bericht op om de magazijnId te bepalen.
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"berichtId":"$berichtId","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"magazijn-a"}"""),
                ),
        )
        WireMockBackendsResource.magazijn!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/pdf")
                        .withBody(byteArrayOf(37, 80, 68, 70)), // "%PDF"
                ),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(200)
            .header("Content-Type", "application/pdf")
            .header("Content-Disposition", "attachment")
    }

    // --- Foutresponses tegen het Problem-schema (RFC 9457), via de validator ---

    @Test
    fun `GET bericht by id - cache-404 levert valide Problem-404`() {
        val id = UUID.randomUUID()
        // Specifieke stub overschrijft de wildcard-200 uit resetStubs().
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(404)),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten/$id")
            .then()
            .statusCode(404)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET bijlage - magazijn-5xx levert valide Problem-502`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        // Cache-lookup OK (wildcard-200 uit resetStubs levert magazijnId=magazijn-a);
        // magazijn-bijlage faalt met 5xx → service mapt naar 502.
        WireMockBackendsResource.magazijn!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"))
                .willReturn(aResponse().withStatus(500)),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(502)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET bericht by id - cache-403 levert valide Problem-403`() {
        val id = UUID.randomUUID()
        // Upstream dwingt de ontvanger-match af; een 403 propageert status-behoudend
        // (4xx-allowlist) en moet als gedeclareerde Problem-403 valideren tegen de spec.
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(403)),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten/$id")
            .then()
            .statusCode(403)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET bijlage - bericht zonder magazijnId levert valide Problem-502`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        // Cache levert een bericht zónder magazijnId (upstream-contractbreuk): de
        // service kan niet routeren en mapt naar 502 i.p.v. een misleidende 500.
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"berichtId":"$berichtId","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z"}"""),
                ),
        )

        given()
            .filter(validator)
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(502)
            .contentType("application/problem+json")
    }

    // --- Input-validatie (400): X-Ontvanger en zoek-parameter `q` ---
    // Geen validator-filter: de triggerende request is bewust spec-ongeldig
    // (ontbrekende/kromme header, ontbrekende verplichte param). We borgen hier
    // dat de afwijzing 400 + application/problem+json is — de PII-invariant dat
    // een ongevalideerde X-Ontvanger nooit de LDV-audittrail of een magazijn raakt.

    @Test
    fun `GET berichten zonder X-Ontvanger levert 400 problem+json`() {
        given()
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET berichten met malformed X-Ontvanger levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", "GEENGELDIGTYPE:123")
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET berichten-zoeken zonder verplichte q levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", ontvanger)
            .`when`()
            .get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET berichten-zoeken met te lange q levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", ontvanger)
            .queryParam("q", "x".repeat(201))
            .`when`()
            .get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    // Borgt dat de generator de spec-grenzen op de query-parameters daadwerkelijk als
    // Bean Validation afdwingt: een out-of-range pagina/paginaGrootte of een lege/te-lange
    // map mag niet stilletjes naar de sessiecache doorlekken maar hoort 400 te geven.

    @Test
    fun `GET berichten met paginaGrootte boven maximum levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", ontvanger)
            .queryParam("paginaGrootte", 201)
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET berichten met paginaGrootte onder minimum levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", ontvanger)
            .queryParam("paginaGrootte", 0)
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    @Test
    fun `GET berichten met negatieve pagina levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", ontvanger)
            .queryParam("pagina", -1)
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }

    // CR-M1: uitvraag harmoniseert `q` minLength met de sessiecache (2), zodat een
    // 1-teken-zoekopdracht hier al op 400 strandt i.p.v. verwarrend door te lekken naar
    // een upstream-400. Bewijst tevens dat de generator de @Size(min=2)-constraint toepast.
    @Test
    fun `GET berichten-zoeken met te korte q levert 400 problem+json`() {
        given()
            .header("X-Ontvanger", ontvanger)
            .queryParam("q", "x")
            .`when`()
            .get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
    }
}
