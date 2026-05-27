package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Vult coverage-gaten in de services aan: pure unit-tests (MockK) tellen niet
 * mee voor quarkus-jacoco, dus de happy paths van [BerichtenlijstService],
 * [BerichtOphaalService] en de bijlage-foutafhandeling worden hier via een
 * @QuarkusTest end-to-end geraakt.
 */
@QuarkusTest
@QuarkusTestResource(WireMockBackendsResource::class)
class ServiceCoverageTest {

    @BeforeEach
    fun reset() {
        WireMockBackendsResource.sessiecache?.resetAll()
        WireMockBackendsResource.magazijn?.resetAll()
    }

    @Test
    fun `lijst met query-parameters bereikt BerichtenlijstService_lijst`() {
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
            .header("X-Ontvanger", "BSN:123456782")
            .queryParam("map", "archief")
            .queryParam("pagina", "1")
            .queryParam("paginaGrootte", "50")
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(200)
    }

    @Test
    fun `zoek met map-parameter bereikt BerichtenlijstService_zoek`() {
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
            .header("X-Ontvanger", "BSN:123456782")
            .queryParam("q", "rente")
            .queryParam("map", "archief")
            .`when`()
            .get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(200)
    }

    @Test
    fun `bericht-detail bereikt BerichtOphaalService_haalBericht`() {
        val id = UUID.randomUUID()
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"berichtId":"$id","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z"}"""),
                ),
        )

        given()
            .header("X-Ontvanger", "BSN:123456782")
            .`when`()
            .get("/api/v1/berichten/$id")
            .then()
            .statusCode(200)
            .body("berichtId", equalTo(id.toString()))
    }

    @Test
    fun `bijlage-error met 5xx vanuit magazijn mapt naar 502 BadGateway`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        WireMockBackendsResource.magazijn!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"))
                .willReturn(aResponse().withStatus(503)),
        )

        given()
            .header("X-Ontvanger", "BSN:123456782")
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(502)
    }

    @Test
    fun `bijlage-error met 404 vanuit magazijn propageert als 404`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        WireMockBackendsResource.magazijn!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"))
                .willReturn(aResponse().withStatus(404)),
        )

        given()
            .header("X-Ontvanger", "BSN:123456782")
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(404)
    }
}
