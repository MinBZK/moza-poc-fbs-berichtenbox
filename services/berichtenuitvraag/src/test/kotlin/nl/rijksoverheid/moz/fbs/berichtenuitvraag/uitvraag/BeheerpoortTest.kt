package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URL

/**
 * De meetgegevens horen niet via het publieke adres op te vragen; health en de spec juist wel,
 * want de probes en de ADR-toets lezen ze daar. Wat de uitgaande aanroepen opleveren, toetst
 * de keten-E2E: daar lopen ze echt.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
class BeheerpoortTest {

    // Relatief aan het beheer-root-pad (`/q`), dus `/q/metrics`.
    @TestHTTPResource(value = "/metrics", management = true)
    lateinit var meetgegevens: URL

    @ParameterizedTest
    @ValueSource(strings = ["/q/metrics", "/metrics", "/api/v1/metrics"])
    fun `de applicatiepoort geeft geen meetgegevens`(pad: String) {
        given().`when`().get(pad).then().statusCode(404)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/q/health/live", "/q/health/ready", "/openapi.json", "/api/v1/openapi.json"])
    fun `health en de spec blijven op de applicatiepoort`(pad: String) {
        given().`when`().get(pad).then().statusCode(200)
    }

    @Test
    fun `een geweigerd verzoek telt per endpoint-template`() {
        // Zonder ontvanger wordt het verzoek geweigerd, maar het telt wel: juist weigeringen wil je zien.
        given().`when`().get("/api/v1/berichten").then().statusCode(400)

        given().`when`().get(meetgegevens).then()
            .statusCode(200)
            .body(containsString("status=\"400\""))
            .body(containsString("uri=\"/berichten\""))
    }

    @Test
    fun `een onbekend pad levert geen eigen reeks op`() {
        given().`when`().get("/api/v1/bestaat-niet-7f3a").then().statusCode(404)

        given().`when`().get(meetgegevens).then()
            .statusCode(200)
            .body(not(containsString("bestaat-niet-7f3a")))
    }

    // Een vaste poort (de Quarkus-default 9000) botst zodra twee diensten een netns delen.
    @Test
    fun `de beheerpoort is de applicatiepoort met een 2 ervoor`() {
        val config = ConfigProvider.getConfig()
        val applicatiepoort = config.getValue("quarkus.http.port", String::class.java)

        assertEquals("2$applicatiepoort", config.getValue("quarkus.management.port", String::class.java))
    }
}
