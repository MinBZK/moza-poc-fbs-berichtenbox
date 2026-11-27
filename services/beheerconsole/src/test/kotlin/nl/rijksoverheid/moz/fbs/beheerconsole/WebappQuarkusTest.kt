package nl.rijksoverheid.moz.fbs.beheerconsole

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * De app wordt op elk app-pad geserveerd, met een CSP die zijn eigen script en stylesheet
 * toelaat; de API en `/q` houden hun eigen, strengere policy.
 */
@QuarkusTest
class WebappQuarkusTest {

    private val webappCsp =
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
            "object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"

    private fun csp(pad: String): List<String> =
        given().redirects().follow(false).`when`().get(pad).then().extract().headers().getValues("Content-Security-Policy")

    // Een scherm dat de router van de app kent, maar waarvoor geen bestand bestaat, krijgt index.html.
    @ParameterizedTest
    @ValueSource(strings = ["/", "/stelsel", "/stelsel/magazijnen"])
    fun `elk app-pad levert de app`(pad: String) {
        given().`when`().get(pad).then()
            .statusCode(200)
            .contentType(containsString("text/html"))
            .body(containsString("<div id=\"root\">"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/", "/stelsel"])
    fun `de app krijgt de webapp-policy, precies een keer`(pad: String) {
        assertEquals(listOf(webappCsp), csp(pad))
    }

    @Test
    fun `de gebouwde scripts staan los en krijgen dezelfde policy`() {
        val index = given().`when`().get("/").then().extract().asString()
        val script = Regex("src=\"(/assets/[^\"]+\\.js)\"").find(index)?.groupValues?.get(1)
            ?: error("geen los script in index.html: $index")

        given().`when`().get(script).then().statusCode(200).contentType(containsString("javascript"))
        assertEquals(listOf(webappCsp), csp(script))
    }

    @Test
    fun `een API-pad krijgt geen app maar de strenge policy`() {
        given().`when`().get("/api/v1/bestaat-niet").then().statusCode(404)
        assertEquals(
            listOf("default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"),
            csp("/api/v1/bestaat-niet"),
        )
    }

    @Test
    fun `het beheerpad blijft beheerpad`() {
        given().`when`().get("/q/health/ready").then().statusCode(200)
        assertEquals(listOf("frame-ancestors 'none'"), csp("/q/health/ready"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["X-Frame-Options", "X-Content-Type-Options", "Strict-Transport-Security", "Referrer-Policy"])
    fun `de app draagt de vaste set headers`(naam: String) {
        assertEquals(1, given().`when`().get("/").then().extract().headers().getValues(naam).size, naam)
    }
}
