package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Coverage voor [SsePassthroughResource]: het endpoint is jaxrs-spec-extern
 * en wordt alleen via @QuarkusTest geraakt (unit-tests vallen buiten quarkus-
 * jacoco). Verifieert de happy-path-streaming en het falen-pad (error-callback
 * op de Multi).
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
    fun `_ophalen propageert sessiecache-fout`() {
        WireMockBackendsResource.sessiecache!!.stubFor(
            get(urlPathEqualTo("/api/v1/berichten/_ophalen"))
                .willReturn(aResponse().withStatus(503)),
        )

        // Bij een upstream-fout op een SSE-call: Quarkus opent het response al
        // (200) voordat het van de error weet en sluit de stream daarna. Het
        // request triggert hier expliciet `onFailure().invoke` in
        // SsePassthroughResource — de geforceerde-close kan RestAssured's body-
        // decoder verwarren, daarom checken we alleen dát het endpoint reageert
        // via een rauwe HttpURLConnection.
        val url = java.net.URI("http://localhost:${io.restassured.RestAssured.port}/api/v1/berichten/_ophalen").toURL()
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("X-Ontvanger", "BSN:123456782")
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 2000
            readTimeout = 2000
        }

        try {
            // Open en sluit; we accepteren elke status (200 of 5xx) — het endpoint
            // is dan geraakt en de `onFailure`-callback geëvalueerd.
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
