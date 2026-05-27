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

    private val ontvanger = "BSN:123456782"

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
                    .withBody("""{"berichtId":"00000000-0000-0000-0000-000000000000","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"default"}"""),
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
                        .withBody("""{"berichtId":"$id","onderwerp":"Test","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"default"}"""),
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
        val body = """{"berichtId":"$id","onderwerp":"Test","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"default"}"""
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
            .patch("/api/v1/berichten/$id")
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
            .delete("/api/v1/berichten/$id")
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
                        .withBody("""{"berichtId":"$berichtId","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"default"}"""),
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
}
