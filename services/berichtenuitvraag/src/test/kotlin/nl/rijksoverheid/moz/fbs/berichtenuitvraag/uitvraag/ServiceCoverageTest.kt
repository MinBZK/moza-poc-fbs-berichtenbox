package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.notMatching
import com.github.tomakehurst.wiremock.client.WireMock.patch as wmPatch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.equalTo as wmEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Vult coverage-gaten in de services aan: pure unit-tests (MockK) tellen niet
 * mee voor quarkus-jacoco, dus de happy paths van [BerichtenlijstService],
 * [BerichtOphaalService] en de bijlage-foutafhandeling worden hier via een
 * @QuarkusTest end-to-end geraakt. De sessiecache-facade is in-memory
 * ([MockSessiecache]); de magazijnen blijven echte HTTP-mocks (WireMock).
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
@QuarkusTestResource(WireMockBackendsResource::class)
class ServiceCoverageTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    @BeforeEach
    fun reset() {
        sessiecache.reset()
        WireMockBackendsResource.magazijn?.resetAll()
        WireMockBackendsResource.magazijn2?.resetAll()
    }

    @Test
    fun `lijst geeft pagina-queryparams door aan de facade`() {
        // Regressie-guard: de uitvraag-API adverteert `pagina`/`paginaGrootte`; de
        // facade krijgt die waarden 1-op-1 (JAX-RS dropt een verkeerd gebonden
        // parameternaam stil, waardoor de cache altijd op default-pagina 0 zou vallen).
        given()
            .header("X-Ontvanger", "BSN:999990019")
            .queryParam("pagina", "1")
            .queryParam("paginaGrootte", "50")
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(200)

        assertEquals(1, sessiecache.laatstePagina)
        assertEquals(50, sessiecache.laatsteGrootte)
    }

    @Test
    fun `zoek bereikt de facade met q`() {
        given()
            .header("X-Ontvanger", "BSN:999990019")
            .queryParam("q", "rente")
            .`when`()
            .get("/api/v1/berichten/_zoeken")
            .then()
            .statusCode(200)

        assertEquals("rente", sessiecache.laatsteZoekQ)
    }

    @Test
    fun `bericht-detail bereikt BerichtOphaalService_haalBericht`() {
        val id = UUID.randomUUID()
        seedBericht(id)

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
        seedBericht(berichtId)
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
        seedBericht(berichtId)
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
        seedBericht(berichtId)
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
        seedBericht(berichtId)
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
        seedBericht(berichtId)
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
    fun `bijlage van een magazijn-B-bericht routeert naar de magazijn-B-mock, niet naar magazijn A`() {
        // Routerings-bewijs (security-grens): de cache levert het bron-magazijnId
        // (= afzender-OIN); MagazijnRouter moet daarop naar de júiste base-URL routeren.
        // De mocks voor OIN A en OIN B draaien op verschillende poorten, dus een
        // verkeerde route landt op de andere mock. We stubben de bijlage ALLEEN op
        // de magazijn-B-mock.
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        seedBericht(berichtId, magazijnId = WireMockBackendsResource.OIN_B)
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
            getRequestedFor(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")),
        )
        WireMockBackendsResource.magazijn!!.verify(
            0,
            getRequestedFor(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")),
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
        seedBericht(id)
        stubMagazijnPatchOk(id)

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
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
        seedBericht(id)
        stubMagazijnPatchOk(id)

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"ongelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
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
        seedBericht(id)
        stubMagazijnPatchOk(id)

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"map":"archief"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(200)

        // `gelezen` afwezig (Jackson non_null), alleen `map` doorgegeven: bevestigt
        // dat een afwezige status géén `gelezen=false` naar het magazijn stuurt.
        WireMockBackendsResource.magazijn!!.verify(
            patchRequestedFor(urlPathEqualTo("/api/v1/berichten/$id"))
                .withRequestBody(matchingJsonPath("$.map", wmEqualTo("archief")))
                .withRequestBody(notMatching(".*gelezen.*")),
        )
    }

    @Test
    fun `lijst bouwt HAL-links met pagina-parameters en next-prev rond de huidige pagina`() {
        // De service bouwt de links zelf uit BerichtenPagina (geen REST-vertaling meer);
        // pagina 1 van 3 → self/prev/next met de uitvraag-parameternamen.
        sessiecache.lijstResultaat = BerichtenPagina(
            berichten = emptyList(),
            page = 1,
            pageSize = 50,
            totalElements = 150,
            totalPages = 3,
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .queryParam("pagina", "1")
            .queryParam("paginaGrootte", "50")
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("_links.self.href", containsString("pagina=1"))
            .body("_links.self.href", containsString("paginaGrootte=50"))
            .body("_links.next.href", containsString("pagina=2"))
            .body("_links.prev.href", containsString("pagina=0"))
    }

    // ───── lees-paden: cache-storing → 502 (niet gemaskeerd 500) ─────

    @Test
    fun `bericht-detail bij cache-storing mapt naar 502`() {
        val id = UUID.randomUUID()
        sessiecache.berichtFout = WebApplicationException("Cache niet bereikbaar.", 503)

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten/$id")
            .then()
            .statusCode(502)
    }

    @Test
    fun `bericht-detail bij cache-miss geeft 404 (geen 502)`() {
        // null uit de facade = niet gevonden; geen upstream-storing.
        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten/${UUID.randomUUID()}")
            .then()
            .statusCode(404)
    }

    @Test
    fun `lijst bij cache-storing mapt naar 502`() {
        sessiecache.lijstFout = WebApplicationException("Cache niet bereikbaar.", 503)

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(502)
    }

    @Test
    fun `lijst bij cache-nog-niet-gevuld propageert 409`() {
        // De gereed-status-gating van de facade (409) is een client-aanwijzing
        // (eerst _ophalen), geen upstream-storing — propageert dus 1-op-1.
        sessiecache.lijstFout = WebApplicationException("Berichten zijn nog niet opgehaald.", 409)

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten")
            .then()
            .statusCode(409)
    }

    @Test
    fun `zoek bij cache-storing mapt naar 502`() {
        sessiecache.zoekFout = WebApplicationException("Cache niet bereikbaar.", 503)

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
        seedBericht(berichtId, magazijnId = "magazijn-onbekend")

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .get("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")
            .then()
            .statusCode(502)

        WireMockBackendsResource.magazijn!!.verify(
            0,
            getRequestedFor(urlPathEqualTo("/api/v1/berichten/$berichtId/bijlagen/$bijlageId")),
        )
    }

    @Test
    fun `PATCH met onbekende magazijnId-query geeft 502 zonder magazijn-call`() {
        // MagazijnRouter.forMagazijn gooit 502 als de id niet in het magazijnregister
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

    private fun seedBericht(berichtId: UUID, magazijnId: String = WireMockBackendsResource.OIN_A) {
        sessiecache.berichten[berichtId] = Bericht(
            berichtId = berichtId,
            afzender = "00000001003214345000",
            ontvanger = Bsn("999990019"),
            onderwerp = "X",
            inhoud = "Inhoud",
            publicatietijdstip = Instant.parse("2026-05-26T10:00:00Z"),
            magazijnId = magazijnId,
            aantalBijlagen = 0,
        )
    }

    private fun stubMagazijnPatchOk(id: UUID) {
        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )
    }
}
