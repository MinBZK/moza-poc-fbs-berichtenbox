package nl.rijksoverheid.moz.fbs.berichtenuitvraag.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.patch as wmPatch
import com.github.tomakehurst.wiremock.client.WireMock.delete as wmDelete
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.WireMockBackendsResource
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Volledige keten door de échte bedrading (CLAUDE.md testlaag 4): HTTP-request →
 * uitvraag-resource → in-process Sessiecache-facade → echte Redis (Dev Services,
 * Redis Stack voor RediSearch) → Profiel-resolver + magazijnen via WireMock →
 * SSE-respons en daaropvolgende reads/writes. Dit is de regressiewacht voor de
 * in-process-herbedrading: een kapotte CDI-wiring, config-key of Redis-interactie
 * faalt hier, niet pas in een omgeving.
 */
@QuarkusTest
@TestProfile(KetenE2eProfile::class)
@QuarkusTestResource(value = WireMockBackendsResource::class, restrictToAnnotatedClass = true)
class UitvraagKetenE2eTest {

    private val profiel get() = WireMockBackendsResource.profiel
    private val magazijnA get() = WireMockBackendsResource.magazijnA
    private val magazijnB get() = WireMockBackendsResource.magazijnB

    @BeforeEach
    fun resetStubs() {
        profiel.resetAll()
        magazijnA.resetAll()
        magazijnB.resetAll()
    }

    private fun stubProfielOptIn(bsn: String, vararg oins: String) {
        val scopes = oins.joinToString(",") {
            """{ "partij": { "identificatieType": "OIN", "identificatieNummer": "$it" } }"""
        }
        profiel.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/$bsn")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"voorkeuren": [ { "voorkeurType": "OntvangViaBerichtenbox", "waarde": "true", "scopes": [ $scopes ] } ]}"""),
            ),
        )
    }

    private fun stubMagazijnBericht(server: WireMockServer, berichtId: String, bsn: String, label: String, afzender: String) {
        server.stubFor(
            get(urlPathMatching("/api/v1/berichten")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                    """
                    {
                      "berichten": [
                        {
                          "berichtId": "$berichtId",
                          "afzender": "$afzender",
                          "ontvanger": { "type": "BSN", "waarde": "$bsn" },
                          "onderwerp": "Bericht van $label",
                          "inhoud": "Inhoud van $label",
                          "publicatietijdstip": "2026-03-10T10:00:00Z",
                          "aantalBijlagen": 0
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
        )
    }

    @Test
    fun `volledige keten - ophalen vult Redis en daarna werken lijst detail patch en delete`() {
        val bsn = "999990019"
        val berichtId = "11111111-1111-1111-1111-111111111111"
        stubProfielOptIn(bsn, OIN_A)
        stubMagazijnBericht(magazijnA, berichtId, bsn, "magazijn-a", OIN_A)

        // Vóór de eerste ophaling weigert de leesweg met 409 (cache nog niet gevuld).
        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(409)

        // Ophalen: SSE-stream eindigt met ophalen-gereed en 1 bericht.
        val sse = given()
            .header("X-Ontvanger", "BSN:$bsn")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(sse.contains("\"event\":\"ophalen-gereed\""), "Verwacht ophalen-gereed in: $sse")
        assertTrue(sse.contains("\"totaalBerichten\":1"), "Verwacht totaalBerichten:1 in: $sse")

        // Lijst uit de (echte) Redis-cache, met uitvraag-parameternamen in de links.
        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten[0].berichtId", equalTo(berichtId))
            .body("berichten[0].magazijnId", equalTo(OIN_A))

        // Detail inclusief inhoud.
        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .`when`().get("/api/v1/berichten/$berichtId")
            .then()
            .statusCode(200)
            .body("inhoud", equalTo("Inhoud van magazijn-a"))

        // Dual-write PATCH: magazijn (WireMock) + cache (Redis) — status komt terug.
        magazijnA.stubFor(
            wmPatch(urlPathMatching("/api/v1/berichten/$berichtId")).willReturn(aResponse().withStatus(204)),
        )

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .header("Content-Type", "application/merge-patch+json")
            .body("""{"status":"gelezen"}""")
            .`when`().patch("/api/v1/berichten/$berichtId?magazijnId=$OIN_A")
            .then()
            .statusCode(200)
            .body("status", equalTo("gelezen"))

        // Dual-write DELETE; daarna is het bericht ook uit de cache verdwenen.
        magazijnA.stubFor(
            wmDelete(urlPathMatching("/api/v1/berichten/$berichtId")).willReturn(aResponse().withStatus(204)),
        )

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .`when`().delete("/api/v1/berichten/$berichtId?magazijnId=$OIN_A")
            .then()
            .statusCode(204)

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .`when`().get("/api/v1/berichten/$berichtId")
            .then()
            .statusCode(404)
    }

    @Test
    fun `partial failure - magazijn-b 500 degradeert maar cachet het overlevende magazijn`() {
        val bsn = "999993653"
        val berichtId = "22222222-2222-2222-2222-222222222222"
        stubProfielOptIn(bsn, OIN_A, OIN_B)
        stubMagazijnBericht(magazijnA, berichtId, bsn, "magazijn-a", OIN_A)
        magazijnB.stubFor(get(urlPathMatching("/.*")).willReturn(aResponse().withStatus(500)))

        val sse = given()
            .header("X-Ontvanger", "BSN:$bsn")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertTrue(sse.contains("\"event\":\"ophalen-gereed\""), "Verwacht ophalen-gereed in: $sse")
        assertTrue(sse.contains("\"geslaagd\":1"), "Verwacht geslaagd:1 in: $sse")
        assertTrue(sse.contains("\"mislukt\":1"), "Verwacht mislukt:1 in: $sse")

        given()
            .header("X-Ontvanger", "BSN:$bsn")
            .`when`().get("/api/v1/berichten")
            .then()
            .statusCode(200)
            .body("berichten[0].berichtId", equalTo(berichtId))
    }

    @Test
    fun `profiel-500 geeft 503 met Retry-After vóór de stream`() {
        profiel.stubFor(
            get(urlEqualTo("/api/profielservice/v1/BSN/999991401")).willReturn(aResponse().withStatus(500)),
        )

        given()
            .header("X-Ontvanger", "BSN:999991401")
            .`when`().get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(503)
            .header("Retry-After", "30")
    }

}

// magazijnId == afzender-OIN (register-conventie). Bron is de gedeelde fixture, zodat
// de stub-OIN's en de geïnjecteerde register-config gegarandeerd dezelfde waarden zijn.
private val OIN_A = WireMockBackendsResource.OIN_A
private val OIN_B = WireMockBackendsResource.OIN_B

/**
 * Echte facade-keten: Redis via Dev Services (Redis Stack — RediSearch is nodig
 * voor de index-bootstrap), géén MockSessiecache-alternative.
 */
class KetenE2eProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "true",
        "quarkus.redis.devservices.image-name" to "redis/redis-stack-server:7.4.0-v3",
    )
}
