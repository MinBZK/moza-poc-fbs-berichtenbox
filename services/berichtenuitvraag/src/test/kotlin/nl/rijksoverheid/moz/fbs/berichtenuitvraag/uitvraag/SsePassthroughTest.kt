package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Coverage voor [SsePassthroughResource]: het endpoint is jaxrs-spec-extern
 * en wordt alleen via @QuarkusTest geraakt (unit-tests vallen buiten quarkus-
 * jacoco). Verifieert de happy-path-streaming, het pre-stream-falen-pad
 * (upstream-fout → 502, geen rauwe out-of-contract-status) en het mid-stream-
 * abort-pad (verbinding breekt ná de headers — stream stopt zonder corrupte/
 * lekkende `data:`-frames).
 */
@QuarkusTest
@QuarkusTestResource(WireMockBackendsResource::class)
class SsePassthroughTest {

    @BeforeEach
    fun reset() {
        WireMockBackendsResource.sessiecache?.resetAll()
    }

    @Test
    fun `_ophalen pijpt SSE-events uit sessiecache 1-op-1 door`() {
        val sseBody = "data: {\"magazijn\":\"a\",\"status\":\"BEZIG\"}\n\ndata: {\"magazijn\":\"a\",\"status\":\"GEREED\"}\n\n"
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/_ophalen"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(sseBody),
                ),
        )

        given()
            .header("X-Ontvanger", "BSN:123456782")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .body(containsString("BEZIG"))
            .body(containsString("GEREED"))
    }

    @Test
    fun `_ophalen behoudt SSE-frame-grenzen zonder dubbel-framing of samenvoegen`() {
        // De kernfunctie is 1-op-1 doorpijpen op event-niveau: de Quarkus REST-client
        // str'ipt de inkomende `data:`-prefix en de server herframe't elk event éénmaal.
        // Een `containsString`-assertie alleen zou groen blijven bij dubbel-framing
        // (`data: data:`) of samengevoegde events; dit borgt het aantal frames + grenzen.
        val sseBody = "data: {\"event\":\"A\"}\n\ndata: {\"event\":\"B\"}\n\n"
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/_ophalen"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(sseBody),
                ),
        )

        val body = given()
            .header("X-Ontvanger", "BSN:123456782")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .extract().body().asString()

        assertFalse(body.contains("data: data:") || body.contains("data:data:"), "dubbel-geframed: $body")
        assertTrue(body.contains("{\"event\":\"A\"}"), "frame A ontbreekt: $body")
        assertTrue(body.contains("{\"event\":\"B\"}"), "frame B ontbreekt: $body")
        assertEquals(2, Regex("(?m)^data:").findAll(body).count(), "verwacht exact 2 SSE-data-frames, body: $body")
    }

    @Test
    fun `_ophalen pijpt partial-failure (1 magazijn OK, 1 FOUT) 1-op-1 door`() {
        // Degradatiegedrag (CLAUDE.md testlaag 4): de sessiecache levert per
        // magazijn een statusevent; één OK, één FOUT. De passthrough mag de
        // degradatie niet maskeren of hertypen — beide events moeten ongewijzigd
        // bij de client aankomen, inclusief het OPHALEN_GEREED-eindevent met de
        // mislukt-telling.
        val sseBody = buildString {
            append("data: {\"event\":\"MAGAZIJN_BEVRAGING_VOLTOOID\",\"magazijnId\":\"magazijn-a\",\"status\":\"OK\"}\n\n")
            append("data: {\"event\":\"MAGAZIJN_BEVRAGING_VOLTOOID\",\"magazijnId\":\"magazijn-b\",\"status\":\"FOUT\"}\n\n")
            append("data: {\"event\":\"OPHALEN_GEREED\",\"geslaagd\":1,\"mislukt\":1}\n\n")
        }
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/_ophalen"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(sseBody),
                ),
        )

        given()
            .header("X-Ontvanger", "BSN:123456782")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(200)
            .body(containsString("\"status\":\"OK\""))
            .body(containsString("\"status\":\"FOUT\""))
            .body(containsString("\"mislukt\":1"))
    }

    @Test
    fun `_ophalen propageert sessiecache-fout met error-status`() {
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/_ophalen"))
                .willReturn(aResponse().withStatus(503)),
        )

        // Bij een upstream-fout op een SSE-call gooit de Quarkus REST-client een
        // WebApplicationException; de `onFailure`-keten in SsePassthroughResource
        // logt op `error` én normaliseert de pre-stream-fout naar het contract
        // (transport/non-4xx → 502, zie M1). RestAssured kan de afgekapte stream
        // niet altijd parsen, dus we vallen terug op een rauwe HttpURLConnection.
        // Afhankelijk van of de SSE-headers al geflusht waren is de status 200
        // (lege/afgekapte stream) of — bij een pre-stream-fout — exact 502 (níét de
        // rauwe upstream-503). In geen geval mag er een `data:`-event doorlekken,
        // want upstream leverde niets.
        val url = java.net.URI("http://localhost:${io.restassured.RestAssured.port}/api/v1/berichten/_ophalen").toURL()
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("X-Ontvanger", "BSN:123456782")
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 2000
            readTimeout = 2000
        }

        try {
            val status = conn.responseCode
            val body = runCatching {
                (if (status >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.readText()
            }.getOrNull().orEmpty()

            // Pre-stream-normalisatie (M1): een echte foutstatus moet 502 zijn (de
            // rauwe upstream-503 mag niet 1-op-1 lekken). 200 blijft toegestaan voor
            // het geval de SSE-headers al geflusht waren vóór de upstream-fout.
            assertTrue(status == 502 || status == 200, "verwacht 502 (genormaliseerd) of voor-fout-streaming-200, kreeg $status")
            assertFalse(body.contains("data:"), "upstream-503 mag geen SSE-data doorlaten, kreeg body: $body")
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `_ophalen breekt veilig af bij mid-stream upstream-abort zonder corrupte data-frames`() {
        // Mid-stream abort (CLAUDE.md testlaag 4, degradatiegedrag): de upstream
        // flusht eerst geldige 200-headers en breekt dán de body af (verbinding
        // reset / malformed chunk). De status ligt op dat moment al vast (kan geen
        // 502 meer worden); de eis is dat de stream gewoon termineert zónder dat er
        // een afgekapt/corrupt `data:`-frame naar de client lekt. We gebruiken een
        // rauwe HttpURLConnection omdat RestAssured de afgebroken stream niet parse't.
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/_ophalen"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withFault(Fault.MALFORMED_RESPONSE_CHUNK),
                ),
        )

        val url = java.net.URI("http://localhost:${io.restassured.RestAssured.port}/api/v1/berichten/_ophalen").toURL()
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("X-Ontvanger", "BSN:123456782")
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 2000
            readTimeout = 2000
        }

        try {
            // De read kan zelf falen (IOException) doordat de verbinding mid-stream
            // breekt — dat is exact het afbreek-gedrag dat we willen: geen vastloper,
            // geen hangende stream. Wat we lezen voordat het breekt, mag geen geldig
            // doorgepijpt SSE-data-frame bevatten (upstream leverde alleen garbage).
            val body = runCatching {
                val status = conn.responseCode

                (if (status >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.readText()
            }.getOrNull().orEmpty()

            assertFalse(body.contains("data:"), "mid-stream-abort mag geen (corrupt) SSE-data-frame doorlaten, kreeg body: $body")
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `_ophalen weigert ontbrekende X-Ontvanger met 400`() {
        // Header-validatie: `@NotNull @Pattern` op de parameter moet 400 geven
        // bij een ontbrekende header (vóór de upstream-call).
        given()
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(400)
    }

    @Test
    fun `_ophalen weigert malformed X-Ontvanger met 400`() {
        given()
            .header("X-Ontvanger", "BSN-123")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .statusCode(400)
    }
}
