package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.delete as wmDelete
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.patch as wmPatch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.http.Fault
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(WireMockBackendsResource::class)
class DualWriteFaultTest {

    @BeforeEach
    fun resetStubs() {
        WireMockBackendsResource.sessiecache?.resetAll()
        WireMockBackendsResource.magazijn?.resetAll()
        // Geen default-lookup-stub meer: BerichtBeheerService raadpleegt de sessiecache
        // niet vóór de write (de client geeft `magazijnId` mee als query-parameter).
    }

    // ───── PATCH ─────

    @Test
    fun `PATCH happy-path doet magazijn-eerst dan cache en geeft 200`() {
        val id = UUID.randomUUID()
        val body = """{"berichtId":"$id","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"magazijn-a"}"""

        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(200)

        WireMockBackendsResource.magazijn!!.verify(patchRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
        WireMockBackendsResource.sessiecache!!.verify(patchRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `PATCH magazijn-5xx normaliseert naar 502 en raakt sessiecache niet aan`() {
        // Magazijn-write-5xx wordt genormaliseerd naar 502 ('502 = upstream-fout'),
        // consistent met het bijlage-pad; de cache wordt niet aangeraakt.
        val id = UUID.randomUUID()
        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(503)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(502)

        WireMockBackendsResource.sessiecache!!.verify(0, patchRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `PATCH cache-faal na magazijn-OK geeft 502 en triggert invalidate-DELETE`() {
        val id = UUID.randomUUID()
        val body = """{"berichtId":"$id","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"magazijn-a"}"""

        // magazijn OK
        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
        )
        // sessiecache PATCH faalt
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(500)),
        )
        // compensatie-DELETE op sessiecache slaagt
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(502)

        WireMockBackendsResource.sessiecache!!.verify(deleteRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `PATCH cache-503 (retriable contentie) na magazijn-OK wordt 502 met invalidate-DELETE`() {
        // Sinds CacheContentieException geeft de sessiecache een retriable 503 bij
        // schrijf-contentie. De uitvraag behandelt elke cache-5xx als upstream-storing:
        // 502 naar de client + compensatie-DELETE — identiek aan het 500-pad. Legt vast
        // dat het retriable 503-signaal hier bewust tot een niet-onderscheiden 502 wordt
        // genormaliseerd (gedragskeuze: zie review M1).
        val id = UUID.randomUUID()
        val body = """{"berichtId":"$id","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"magazijn-a"}"""

        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(503)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(502)

        WireMockBackendsResource.sessiecache!!.verify(deleteRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    // ───── DELETE ─────

    @Test
    fun `DELETE happy-path doet magazijn-eerst dan cache en geeft 204`() {
        val id = UUID.randomUUID()
        WireMockBackendsResource.magazijn!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(204)
    }

    @Test
    fun `DELETE magazijn-5xx normaliseert naar 502 en raakt cache niet aan`() {
        val id = UUID.randomUUID()
        WireMockBackendsResource.magazijn!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(503)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(502)

        WireMockBackendsResource.sessiecache!!.verify(0, deleteRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `PATCH cache-4xx na magazijn-OK propageert 4xx zonder compensatie`() {
        // 4xx = contract-bug (geen transport-storing) — moet 1-op-1 propageren
        // i.p.v. als 502 maskeren, anders ziet ops "magazijn bijgewerkt" terwijl
        // het probleem in de cache-implementatie zit.
        val id = UUID.randomUUID()
        val body = """{"berichtId":"$id","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"magazijn-a"}"""

        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(404)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(404)

        // Geen compensatie-DELETE bij 4xx.
        WireMockBackendsResource.sessiecache!!.verify(0, deleteRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `DELETE cache-4xx na magazijn-OK propageert 4xx zonder compensatie`() {
        val id = UUID.randomUUID()
        WireMockBackendsResource.magazijn!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(404)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(404)

        WireMockBackendsResource.sessiecache!!.verify(1, deleteRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `PATCH cache-transport-fout (connection reset) na magazijn-OK geeft 502`() {
        // Connection-reset triggert een ProcessingException in de REST-client
        // (geen WAE met status). Dat valt onder "transport-fout" → 502 + invalidate.
        val id = UUID.randomUUID()
        val body = """{"berichtId":"$id","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"magazijn-a"}"""

        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(502)

        WireMockBackendsResource.sessiecache!!.verify(deleteRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `DELETE cache-transport-fout (connection reset) na magazijn-OK geeft 502`() {
        val id = UUID.randomUUID()
        WireMockBackendsResource.magazijn!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .inScenario("delete-cache-reset")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("after-reset"),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .inScenario("delete-cache-reset")
                .whenScenarioStateIs("after-reset")
                .willReturn(aResponse().withStatus(204)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(502)

        WireMockBackendsResource.sessiecache!!.verify(2, deleteRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    // ───── compensatie-invalidate faalt ─────

    @Test
    fun `PATCH compensatie-invalidate 5xx blijft 502 (cache stale tot TTL)`() {
        // Cache-PATCH faalt (5xx) én de compensatie-DELETE faalt ook (5xx). De
        // operatie blijft 502; de cache mag stale blijven tot de TTL verloopt.
        val id = UUID.randomUUID()
        val body = """{"berichtId":"$id","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"magazijn-a"}"""

        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(500)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(500)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(502)

        WireMockBackendsResource.sessiecache!!.verify(deleteRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `DELETE compensatie-invalidate transport-fout blijft 502 (cache stale tot TTL)`() {
        // Cache-DELETE faalt (5xx) en de compensatie-DELETE breekt af met een
        // connection-reset (ProcessingException). Nog steeds 502; warn, geen throw.
        val id = UUID.randomUUID()
        WireMockBackendsResource.magazijn!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .inScenario("compensatie-reset")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("compensatie"),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .inScenario("compensatie-reset")
                .whenScenarioStateIs("compensatie")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(502)

        WireMockBackendsResource.sessiecache!!.verify(2, deleteRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `DELETE cache-faal na magazijn-OK geeft 502 met compensatie-DELETE`() {
        val id = UUID.randomUUID()
        WireMockBackendsResource.magazijn!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(204)),
        )
        // sessiecache DELETE faalt eerst, slaagt bij compensatie (tweede call)
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .inScenario("cache-delete")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("after-first-fail"),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .inScenario("cache-delete")
                .whenScenarioStateIs("after-first-fail")
                .willReturn(aResponse().withStatus(204)),
        )

        given()
            .header("X-Ontvanger", "BSN:999990019")
            .`when`()
            .delete("/api/v1/berichten/$id?magazijnId=magazijn-a")
            .then()
            .statusCode(502)

        WireMockBackendsResource.sessiecache!!.verify(2, deleteRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

}
