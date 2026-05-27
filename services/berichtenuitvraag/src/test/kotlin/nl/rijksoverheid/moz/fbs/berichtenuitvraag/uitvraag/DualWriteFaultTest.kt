package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.delete as wmDelete
import com.github.tomakehurst.wiremock.client.WireMock.patch as wmPatch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
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
    }

    // ───── PATCH ─────

    @Test
    fun `PATCH happy-path doet magazijn-eerst dan cache en geeft 200`() {
        val id = UUID.randomUUID()
        val body = """{"berichtId":"$id","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"default"}"""

        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
        )

        given()
            .header("X-Ontvanger", "BSN:123456782")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id")
            .then()
            .statusCode(200)

        WireMockBackendsResource.magazijn!!.verify(patchRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
        WireMockBackendsResource.sessiecache!!.verify(patchRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `PATCH magazijn-faal geeft 5xx en raakt sessiecache niet aan`() {
        val id = UUID.randomUUID()
        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(503)),
        )

        given()
            .header("X-Ontvanger", "BSN:123456782")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id")
            .then()
            .statusCode(503)

        WireMockBackendsResource.sessiecache!!.verify(0, patchRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `PATCH cache-faal na magazijn-OK geeft 502 en triggert invalidate-DELETE`() {
        val id = UUID.randomUUID()
        val body = """{"berichtId":"$id","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"default"}"""

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
            .header("X-Ontvanger", "BSN:123456782")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id")
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
            .header("X-Ontvanger", "BSN:123456782")
            .`when`()
            .delete("/api/v1/berichten/$id")
            .then()
            .statusCode(204)
    }

    @Test
    fun `DELETE magazijn-faal geeft 5xx en raakt cache niet aan`() {
        val id = UUID.randomUUID()
        WireMockBackendsResource.magazijn!!.stubFor(
            wmDelete(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(503)),
        )

        given()
            .header("X-Ontvanger", "BSN:123456782")
            .`when`()
            .delete("/api/v1/berichten/$id")
            .then()
            .statusCode(503)

        WireMockBackendsResource.sessiecache!!.verify(0, deleteRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `PATCH cache-4xx na magazijn-OK propageert 4xx zonder compensatie`() {
        // 4xx = contract-bug (geen transport-storing) — moet 1-op-1 propageren
        // i.p.v. als 502 maskeren, anders ziet ops "magazijn bijgewerkt" terwijl
        // het probleem in de cache-implementatie zit.
        val id = UUID.randomUUID()
        val body = """{"berichtId":"$id","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"default"}"""

        WireMockBackendsResource.magazijn!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
        )
        WireMockBackendsResource.sessiecache!!.stubFor(
            wmPatch(urlPathEqualTo("/api/v1/berichten/$id"))
                .willReturn(aResponse().withStatus(404)),
        )

        given()
            .header("X-Ontvanger", "BSN:123456782")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id")
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
            .header("X-Ontvanger", "BSN:123456782")
            .`when`()
            .delete("/api/v1/berichten/$id")
            .then()
            .statusCode(404)

        WireMockBackendsResource.sessiecache!!.verify(1, deleteRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }

    @Test
    fun `PATCH cache-transport-fout (connection reset) na magazijn-OK geeft 502`() {
        // Connection-reset triggert een ProcessingException in de REST-client
        // (geen WAE met status). Dat valt onder "transport-fout" → 502 + invalidate.
        val id = UUID.randomUUID()
        val body = """{"berichtId":"$id","onderwerp":"X","publicatietijdstip":"2026-05-26T10:00:00Z","magazijnId":"default"}"""

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
            .header("X-Ontvanger", "BSN:123456782")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`()
            .patch("/api/v1/berichten/$id")
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
            .header("X-Ontvanger", "BSN:123456782")
            .`when`()
            .delete("/api/v1/berichten/$id")
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
            .header("X-Ontvanger", "BSN:123456782")
            .`when`()
            .delete("/api/v1/berichten/$id")
            .then()
            .statusCode(502)

        WireMockBackendsResource.sessiecache!!.verify(2, deleteRequestedFor(urlPathEqualTo("/api/v1/berichten/$id")))
    }
}
