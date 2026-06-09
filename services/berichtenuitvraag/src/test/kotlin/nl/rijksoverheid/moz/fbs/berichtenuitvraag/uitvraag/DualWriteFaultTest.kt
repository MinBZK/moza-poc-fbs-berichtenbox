package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.delete as wmDelete
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.patch as wmPatch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Dual-write-foutpaden: magazijn (bron van waarheid, WireMock) eerst, daarna de
 * in-process cache-facade ([MockSessiecache] met fout-injectie). Pint de
 * compensatie-semantiek: cache-5xx na een geslaagde magazijn-write → invalidate
 * + 502; cache-4xx propageert zonder compensatie.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
@QuarkusTestResource(WireMockBackendsResource::class)
class DualWriteFaultTest {

    @Inject
    lateinit var sessiecache: MockSessiecache

    @BeforeEach
    fun reset() {
        sessiecache.reset()
        WireMockBackendsResource.magazijn?.resetAll()
    }

    private fun seedBericht(id: UUID) {
        sessiecache.berichten[id] = Bericht(
            berichtId = id,
            afzender = "00000001003214345000",
            ontvanger = Bsn("999990019"),
            onderwerp = "X",
            inhoud = "Inhoud",
            publicatietijdstip = Instant.parse("2026-05-26T10:00:00Z"),
            magazijnId = WireMockBackendsResource.OIN_A,
            aantalBijlagen = 0,
        )
    }

    private fun stubMagazijnPatchOk(id: UUID) {
        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )
    }

    private fun stubMagazijnDeleteOk(id: UUID) {
        WireMockBackendsResource.magazijn!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )
    }

    private fun cacheStoring() = SessiecacheException.Onbereikbaar("Cache niet bereikbaar.")

    // ───── PATCH ─────

    @Test
    fun `PATCH happy-path doet magazijn-eerst dan cache en geeft 200`() {
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

        WireMockBackendsResource.magazijn!!.verify(patchRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))

        assertEquals(1, sessiecache.werkBijAanroepen)
    }

    @Test
    fun `PATCH met lege patch geeft 400 zonder magazijn-write`() {
        // Spec: minProperties 1. De afwijzing gebeurt vóór de magazijn-write zodat
        // een no-op geen mutatie-pad raakt en geen desync-alert veroorzaakt.
        val id = UUID.randomUUID()

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(400)

        WireMockBackendsResource.magazijn!!.verify(0, patchRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))

        assertEquals(0, sessiecache.werkBijAanroepen)
    }

    @Test
    fun `PATCH magazijn-5xx normaliseert naar 502 en raakt de cache niet aan`() {
        // Magazijn-write-5xx wordt genormaliseerd naar 502 ('502 = upstream-fout'),
        // consistent met het bijlage-pad; de cache wordt niet aangeraakt.
        val id = UUID.randomUUID()
        seedBericht(id)
        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(503)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(502)

        assertEquals(0, sessiecache.werkBijAanroepen)
    }

    @Test
    fun `PATCH magazijn-403 propageert 1-op-1 zonder cache-call`() {
        // Autorisatie afgewezen door het magazijn (4xx) is geen transport-storing:
        // de status moet 1-op-1 naar de client, niet hergetypeerd naar 502, en de
        // cache mag niet aangeraakt worden (magazijn-write is niet doorgegaan).
        val id = UUID.randomUUID()
        seedBericht(id)
        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(403)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(403)

        assertEquals(0, sessiecache.werkBijAanroepen)
    }

    @Test
    fun `PATCH cache-faal na magazijn-OK geeft 502 en triggert compensatie-invalidate`() {
        val id = UUID.randomUUID()
        seedBericht(id)
        stubMagazijnPatchOk(id)
        sessiecache.werkBijFouten += cacheStoring()

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(502)

        assertEquals(listOf(id), sessiecache.verwijderAanroepen)
    }

    @Test
    fun `PATCH cache-niet-storing na magazijn-OK propageert status zonder compensatie`() {
        // Een niet-storing cache-fout (OngeldigeInvoer → 400, geen transport-storing) moet
        // 1-op-1 propageren i.p.v. als 502 maskeren, anders ziet ops "magazijn bijgewerkt"
        // terwijl het probleem in de cache-implementatie zit.
        val id = UUID.randomUUID()
        seedBericht(id)
        stubMagazijnPatchOk(id)
        sessiecache.werkBijFouten += SessiecacheException.OngeldigeInvoer("cache-ongeldig")

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(400)

        assertEquals(emptyList<UUID>(), sessiecache.verwijderAanroepen)
    }

    @Test
    fun `PATCH cache-miss na magazijn-OK geeft 404 zonder compensatie`() {
        // Bericht bestaat in het magazijn (write slaagde) maar niet meer in de cache
        // (TTL verlopen of eerdere invalidate): 404 met desync-alert in de log;
        // compenseren heeft geen zin (er valt niets te invalideren).
        val id = UUID.randomUUID()
        stubMagazijnPatchOk(id)

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(404)

        assertEquals(emptyList<UUID>(), sessiecache.verwijderAanroepen)
    }

    @Test
    fun `PATCH compensatie-invalidate-faal blijft 502 (cache stale tot TTL)`() {
        // Cache-PATCH faalt én de compensatie-invalidate faalt ook. De operatie
        // blijft 502; de cache mag stale blijven tot de TTL verloopt.
        val id = UUID.randomUUID()
        seedBericht(id)
        stubMagazijnPatchOk(id)
        sessiecache.werkBijFouten += cacheStoring()
        sessiecache.verwijderFouten += cacheStoring()

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(502)

        assertEquals(listOf(id), sessiecache.verwijderAanroepen)
    }

    // ───── DELETE ─────

    @Test
    fun `DELETE happy-path doet magazijn-eerst dan cache en geeft 204`() {
        val id = UUID.randomUUID()
        seedBericht(id)
        stubMagazijnDeleteOk(id)

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(204)

        WireMockBackendsResource.magazijn!!.verify(deleteRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))

        assertEquals(listOf(id), sessiecache.verwijderAanroepen)
    }

    @Test
    fun `DELETE magazijn-5xx normaliseert naar 502 en raakt de cache niet aan`() {
        val id = UUID.randomUUID()
        WireMockBackendsResource.magazijn!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(503)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(502)

        assertEquals(emptyList<UUID>(), sessiecache.verwijderAanroepen)
    }

    @Test
    fun `DELETE cache-faal na magazijn-OK geeft 502 met compensatie-invalidate`() {
        // Eerste cache-delete faalt; de compensatie (tweede call) slaagt.
        val id = UUID.randomUUID()
        seedBericht(id)
        stubMagazijnDeleteOk(id)
        sessiecache.verwijderFouten += cacheStoring()

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(502)

        assertEquals(listOf(id, id), sessiecache.verwijderAanroepen)
    }

    @Test
    fun `DELETE cache-faal en compensatie-faal blijft 502 (cache stale tot TTL)`() {
        val id = UUID.randomUUID()
        seedBericht(id)
        stubMagazijnDeleteOk(id)
        sessiecache.verwijderFouten += cacheStoring()
        sessiecache.verwijderFouten += cacheStoring()

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(502)

        assertEquals(listOf(id, id), sessiecache.verwijderAanroepen)
    }

    @Test
    fun `DELETE cache-niet-storing na magazijn-OK propageert status zonder compensatie`() {
        val id = UUID.randomUUID()
        seedBericht(id)
        stubMagazijnDeleteOk(id)
        sessiecache.verwijderFouten += SessiecacheException.OngeldigeInvoer("cache-ongeldig")

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=${WireMockBackendsResource.OIN_A}")
            .then()
            .statusCode(400)

        // Eén call (de write die de niet-storing-fout gaf); geen tweede compensatie-call.
        assertEquals(listOf(id), sessiecache.verwijderAanroepen)
    }
}
