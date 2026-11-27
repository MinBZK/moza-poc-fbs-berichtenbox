package nl.rijksoverheid.moz.fbs.stelselregister

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import java.io.File
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URL

/**
 * De meetgegevens horen niet via het publieke adres op te vragen; health en de spec juist wel,
 * want de probes en de ADR-toets lezen ze daar.
 */
@QuarkusTest
class BeheerpoortTest {

    // Relatief aan het beheer-root-pad (`/q`), dus `/q/metrics`.
    @TestHTTPResource(value = "/metrics", management = true)
    lateinit var meetgegevens: URL

    @ParameterizedTest
    @ValueSource(strings = ["/q/metrics", "/metrics", "/q/prometheus"])
    fun `de applicatiepoort geeft geen meetgegevens`(pad: String) {
        given().`when`().get(pad).then().statusCode(404)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/q/health/live", "/q/health/ready", "/openapi.json"])
    fun `health en de spec blijven op de applicatiepoort`(pad: String) {
        given().`when`().get(pad).then().statusCode(200)
    }

    @Test
    fun `de beheerpoort telt verzoeken per endpoint-template`() {
        given().`when`().get("/api/v1/stelseldocument").then().statusCode(200)

        given().`when`().get(meetgegevens).then()
            .statusCode(200)
            .body(containsString("http_server_requests_seconds_count{"))
            // Quarkus REST noemt het template zonder het root-pad `/api/v1`.
            .body(containsString("uri=\"/stelseldocument\""))
    }

    @Test
    fun `een onbekend pad levert geen eigen reeks op`() {
        given().`when`().get("/api/v1/bestaat-niet-7f3a").then().statusCode(404)

        given().`when`().get(meetgegevens).then()
            .statusCode(200)
            .body(not(containsString("bestaat-niet-7f3a")))
    }

    // Een vaste poort (de Quarkus-default 9000) botst zodra twee diensten een netns delen. Getoetst
    // op de regel zelf: in een test kiest Quarkus beide poorten vrij, dus de draaiende waarden
    // zeggen niets over de afleiding.
    @Test
    fun `de beheerpoort is de applicatiepoort met een 2 ervoor`() {
        val regels = File("src/main/resources/application.properties").readLines()

        assertEquals(listOf("quarkus.management.port=2\${quarkus.http.port}"), regels.filter { it.startsWith("quarkus.management.port=") })
    }
}
