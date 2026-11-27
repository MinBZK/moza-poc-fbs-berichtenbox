package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import java.io.File
import java.util.UUID
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    // Een vaste poort (de Quarkus-default 9000) botst zodra twee diensten een netns delen. Getoetst
    // op de regel zelf: in een test kiest Quarkus beide poorten vrij, dus de draaiende waarden
    // zeggen niets over de afleiding.
    @Test
    fun `de beheerpoort is de applicatiepoort met een 2 ervoor`() {
        val regels = File("src/main/resources/application.properties").readLines()

        assertEquals(listOf("quarkus.management.port=2\${quarkus.http.port}"), regels.filter { it.startsWith("quarkus.management.port=") })
    }

    // Een 404 is altijd al NOT_FOUND; dit toetst de instelling die andere antwoorden zonder
    // template (hier een 405) onder UNKNOWN laat tellen in plaats van onder het ruwe pad.
    @Test
    fun `een antwoord zonder template telt niet onder zijn pad`() {
        val id = UUID.randomUUID().toString()

        given().`when`().put("/api/v1/berichten/$id").then().statusCode(405)

        val tekst = given().`when`().get(meetgegevens).then().statusCode(200).extract().asString()

        assertFalse(id in tekst, "ruw pad in de meetgegevens")
        assertTrue(tekst.lines().any { "status=\"405\"" in it && "uri=\"UNKNOWN\"" in it }, "geen 405 onder UNKNOWN")
    }

    // RESTEasy beantwoordt OPTIONS op een bestaand pad zelf, vóór de meting het template kent.
    @Test
    fun `een OPTIONS-verzoek telt niet onder zijn pad`() {
        val id = UUID.randomUUID().toString()

        given().`when`().options("/api/v1/berichten/$id").then().statusCode(200)

        val tekst = given().`when`().get(meetgegevens).then().statusCode(200).extract().asString()

        assertFalse(id in tekst, "ruw pad in de meetgegevens")
        assertTrue(tekst.lines().any { "method=\"OPTIONS\"" in it && "uri=\"OPTIONS\"" in it }, "geen OPTIONS-reeks")
    }

    @Test
    fun `de beheerpoort luistert op dezelfde interface als de applicatiepoort`() {
        val regels = File("src/main/resources/application.properties").readLines()

        // Ook een profielregel (%prod.quarkus.management.host=...) zou de afleiding stil overschrijven.
        assertEquals(listOf("quarkus.management.host=\${quarkus.http.host}"), regels.filter { "quarkus.management.host=" in it })
    }
}
