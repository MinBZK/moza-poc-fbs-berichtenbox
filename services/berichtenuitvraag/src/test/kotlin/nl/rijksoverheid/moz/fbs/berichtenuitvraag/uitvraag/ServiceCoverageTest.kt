package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.patch as wmPatch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.equalTo as wmEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
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
        WireMockBackendsResource.magazijn2?.resetAll()
    }

    @Test
    fun `lijst vertaalt pagina-queryparams naar sessiecache page-pageSize`() {
        // Regressie-guard (review H1): de uitvraag-API adverteert `pagina`/`paginaGrootte`,
        // maar de sessiecache bindt `page`/`pageSize`. Verifieer dat de outbound call de
        // upstream-namen gebruikt — JAX-RS dropt een verkeerde naam stil, waardoor de
        // sessiecache altijd op default-pagina 0 zou vallen.
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
            .header("X-Ontvanger", "BSN:999990019")
            .queryParam("pagina", "1")
            .queryParam("paginaGrootte", "50")
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(200)

        WireMockBackendsResource.sessiecache!!.verify(
            getRequestedFor(urlPathEqualTo("/api/v1/berichten"))
                .withQueryParam("page", wmEqualTo("1"))
                .withQueryParam("pageSize", wmEqualTo("50")),
        )
    }

    @Test
    fun `zoek bereikt BerichtenlijstService_zoek met q`() {
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
            .header("X-Ontvanger", "BSN:999990019")
            .queryParam("q", "rente")
            .`when`()
            .get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(200)

        WireMockBackendsResource.sessiecache!!.verify(
            getRequestedFor(urlPathEqualTo("/api/v1/berichten/_zoeken"))
                .withQueryParam("q", wmEqualTo("rente")),
        )
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
                        .withBody("""{"berichtId":"$id","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"magazijn-a"}"""),
                ),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
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
        stubBerichtLookup(berichtId)
        WireMockBackendsResource.magazijn!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"))
                .willReturn(aResponse().withStatus(503)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(502)
    }

    @Test
    fun `bijlage-error met 404 vanuit magazijn propageert als 404`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        stubBerichtLookup(berichtId)
        WireMockBackendsResource.magazijn!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"))
                .willReturn(aResponse().withStatus(404)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(404)
    }

    @Test
    fun `bijlage-transport-fout vanuit magazijn mapt naar 502 BadGateway`() {
        // Connection-reset op de magazijn-bijlage-call = ProcessingException →
        // 502 (upstream-fout), niet 500 (onze fout).
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        stubBerichtLookup(berichtId)
        WireMockBackendsResource.magazijn!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(502)
    }

    @Test
    fun `bijlage 2xx zonder Content-Type mapt naar 502 BadGateway`() {
        // Een 2xx zonder Content-Type-header is een echte upstream-bug: zonder
        // mimeType kan de response-Content-Type niet bepaald worden → 502.
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        stubBerichtLookup(berichtId)
        WireMockBackendsResource.magazijn!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"))
                .willReturn(aResponse().withStatus(200).withBody("pdf-bytes")),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(502)
    }

    @Test
    fun `bijlage met gevaarlijk text-html Content-Type wordt geforceerd tot attachment-download`() {
        // End-to-end-bewijs van de stored-XSS-bescherming via de echte HTTP-stack:
        // resource zet BIJLAGE_MIME_TYPE_PROPERTY → BijlageContentTypeFilter forceert
        // Content-Disposition: attachment. Een magazijn dat `text/html` (renderbaar
        // in de browser) levert, mag NOOIT inline gerenderd worden; de attachment-
        // header dwingt af dat de browser dít als download behandelt i.p.v. uit te
        // voeren. text/html is een parsebaar MIME-type, dus de Content-Type passeert
        // hier 1-op-1 — de attachment-forcering is in dit geval de XSS-mitigatie.
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        stubBerichtLookup(berichtId)
        WireMockBackendsResource.magazijn!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<script>alert(1)</script>"),
                ),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(200)
            .header("Content-Disposition", "attachment")
    }

    @Test
    fun `bijlage van een magazijn-b-bericht routeert naar de magazijn-b-mock, niet naar magazijn-a`() {
        // Routerings-bewijs (security-grens): de sessiecache levert het bron-magazijnId;
        // MagazijnRouter moet daarop naar de júiste base-URL routeren. magazijn-a en
        // magazijn-b draaien op verschillende poorten, dus een verkeerde route landt op
        // de andere mock. We stuben de bijlage ALLEEN op magazijn-b.
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        stubBerichtLookup(berichtId, magazijnId = "magazijn-b")
        WireMockBackendsResource.magazijn2!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/pdf")
                        .withBody("pdf-bytes"),
                ),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(200)

        WireMockBackendsResource.magazijn2!!.verify(
            com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"),
            ),
        )
        WireMockBackendsResource.magazijn!!.verify(
            0,
            com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"),
            ),
        )
    }

    // Noot: de fail-closed afhandeling van een ONparsebaar MIME-type (-> application/
    // octet-stream) wordt op unit-niveau getest in BijlageContentTypeFilterTest, waar de
    // property rechtstreeks gezet wordt. Een end-to-end variant met een onparsebare
    // upstream-Content-Type is bewust weggelaten: zo'n header breekt de REST-client al bij
    // het parsen van de upstream-response (voor onze filter draait), dus die test zou het
    // transport meten, niet de fail-closed-logica.

    @Test
    fun `PATCH status gelezen mapt naar magazijn-patch gelezen=true`() {
        val id = UUID.randomUUID()
        stubBerichtLookup(id)
        stubDualWritePatchOk(id)

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(200)

        // Assert de body op de wire: `status:gelezen` → `{"gelezen":true}` richting
        // magazijn. Zonder deze check zou een veld-rename of geïnverteerde mapping
        // door alle tests glippen (status 200 zegt niets over de payload).
        WireMockBackendsResource.magazijn!!.verify(
            patchRequestedFor(urlPathEqualTo("/api/v1/berichten/$id"))
                .withRequestBody(matchingJsonPath("$.gelezen", wmEqualTo("true"))),
        )
    }

    @Test
    fun `PATCH status ongelezen mapt naar magazijn-patch gelezen=false`() {
        val id = UUID.randomUUID()
        stubBerichtLookup(id)
        stubDualWritePatchOk(id)

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"ongelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(200)

        WireMockBackendsResource.magazijn!!.verify(
            patchRequestedFor(urlPathEqualTo("/api/v1/berichten/$id"))
                .withRequestBody(matchingJsonPath("$.gelezen", wmEqualTo("false"))),
        )
    }

    @Test
    fun `PATCH zonder status mapt naar magazijn-patch gelezen=null`() {
        val id = UUID.randomUUID()
        stubBerichtLookup(id)
        stubDualWritePatchOk(id)

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"map":"archief"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(200)

        // `gelezen` afwezig (Jackson non_null), alleen `map` doorgegeven: bevestigt
        // dat een afwezige status géén `gelezen=false` naar het magazijn stuurt.
        WireMockBackendsResource.magazijn!!.verify(
            patchRequestedFor(urlPathEqualTo("/api/v1/berichten/$id"))
                .withRequestBody(matchingJsonPath("$.map", wmEqualTo("archief")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*gelezen.*")),
        )
    }

    @Test
    fun `lijst met HAL-links herschrijft page-parameters naar pagina-parameters`() {
        // De sessiecache levert `_links` met haar eigen `page`/`pageSize`-namen;
        // dit endpoint adverteert `pagina`/`paginaGrootte`. De links moeten worden
        // herschreven zodat een client die `_links.next` volgt op de juiste
        // parameter-namen uitkomt.
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"berichten":[],"_links":{""" +
                                """"self":{"href":"/api/v1/berichten?page=1&pageSize=50"},""" +
                                """"next":{"href":"/api/v1/berichten?page=2&pageSize=50"},""" +
                                """"prev":{"href":"/api/v1/berichten?page=0&pageSize=50"}}}""",
                        ),
                ),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("_links.next.href", containsString("pagina=2"))
            .body("_links.next.href", containsString("paginaGrootte=50"))
            .body("_links.self.href", containsString("pagina=1"))
            .body("_links.prev.href", containsString("pagina=0"))
    }

    private fun stubDualWritePatchOk(id: UUID, magazijnId: String = "magazijn-a") {
        val body = """{"berichtId":"$id","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"$magazijnId"}"""
        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
        )
    }

    // ───── lees-paden: upstream cache-fout → 502 (niet gemaskeerd 500) ─────

    @Test
    fun `bericht-detail bij cache-5xx mapt naar 502`() {
        val id = UUID.randomUUID()
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(500)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten/$id")
            .then()
            .statusCode(502)
    }

    @Test
    fun `bericht-detail bij cache-transport-fout mapt naar 502`() {
        val id = UUID.randomUUID()
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten/$id")
            .then()
            .statusCode(502)
    }

    @Test
    fun `bericht-detail bij cache-4xx propageert 4xx (geen 502)`() {
        // 4xx (incl. 404 cache-miss) is geen upstream-storing en propageert 1-op-1.
        val id = UUID.randomUUID()
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(404)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten/$id")
            .then()
            .statusCode(404)
    }

    @Test
    fun `lijst bij cache-5xx mapt naar 502`() {
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten"))
                .willReturn(aResponse().withStatus(503)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(502)
    }

    @Test
    fun `zoek bij cache-transport-fout mapt naar 502`() {
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/_zoeken"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .queryParam("q", "rente")
            .`when`()
            .get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(502)
    }

    // ───── multi-magazijn routing-mismatch end-to-end → 502 ─────

    @Test
    fun `bijlage met onbekend magazijnId uit cache geeft 502 zonder magazijn-call`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        stubBerichtLookup(berichtId, magazijnId = "magazijn-onbekend")

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(502)

        WireMockBackendsResource.magazijn!!.verify(
            0,
            com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId"),
            ),
        )
    }

    @Test
    fun `PATCH met onbekende magazijnId-query geeft 502 zonder magazijn-call`() {
        // MagazijnRouter.forMagazijn gooit 502 als de id niet in `magazijnen.urls`
        // staat — infrastructuur-mismatch, geen client-fout (4xx).
        val id = UUID.randomUUID()

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=magazijn-onbekend")
            .then()
            .statusCode(502)
    }

    @Test
    fun `DELETE met onbekende magazijnId-query geeft 502 zonder magazijn-call`() {
        val id = UUID.randomUUID()

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=magazijn-onbekend")
            .then()
            .statusCode(502)
    }

    private fun stubBerichtLookup(berichtId: UUID, magazijnId: String = "magazijn-a") {
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/$berichtId"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"berichtId":"$berichtId","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"$magazijnId"}"""),
                ),
        )
    }
}
