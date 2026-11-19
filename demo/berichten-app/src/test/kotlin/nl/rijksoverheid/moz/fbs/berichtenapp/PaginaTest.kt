package nl.rijksoverheid.moz.fbs.berichtenapp

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@QuarkusTest
class PaginaTest {

    @Test
    fun `de pagina komt met een CSP die alleen eigen script toestaat en alleen de App Manager als bestemming`() {
        val csp = given().get("/").then().statusCode(200)
            .header("Referrer-Policy", "no-referrer")
            .header("X-Content-Type-Options", "nosniff")
            .header("X-Frame-Options", "DENY")
            .header("Cross-Origin-Opener-Policy", "same-origin")
            .header("Strict-Transport-Security", "max-age=31536000")
            .extract().header("Content-Security-Policy")
        val richtlijnen = csp.split(';').map(String::trim).associate { it.substringBefore(' ') to it.substringAfter(' ', "") }

        // De hele set: een richtlijn die erbij komt (of een tweede bron) moet hier bewust bij.
        assertEquals(
            mapOf(
                "default-src" to "'none'",
                "script-src" to "'self'",
                "style-src" to "'self'",
                "connect-src" to "'self' https://app-manager.test/",
                "img-src" to "'self'",
                "frame-ancestors" to "'none'",
                "base-uri" to "'none'",
                "form-action" to "'none'",
                "require-trusted-types-for" to "'script'",
                "trusted-types" to "'none'",
            ),
            richtlijnen,
        )
    }

    @Test
    fun `de pagina heeft geen inline script, geen inline stijl en geen event-handlers`() {
        val html = given().get("/").then().statusCode(200).extract().asString()

        assertFalse(Regex("<script(?![^>]*\\bsrc=)").containsMatchIn(html), "inline script")
        assertFalse(Regex("<style|\\sstyle=|\\son[a-z]+=", RegexOption.IGNORE_CASE).containsMatchIn(html), "inline stijl of handler")
    }

    @Test
    fun `het vangnet laadt als gewoon script vóór de module, zodat het ook werkt als de module niet laadt`() {
        val html = given().get("/").then().statusCode(200).extract().asString()
        val vangnet = html.indexOf("<script src=\"js/vangnet.js\" defer></script>")
        val module = html.indexOf("<script type=\"module\" src=\"js/app.js\"></script>")

        assertTrue(vangnet in 0 until module, "vangnet.js ontbreekt of staat na app.js")
    }

    @Test
    fun `de configuratie noemt adres en OIN van de App Manager`() {
        given().get("/config.json").then().statusCode(200)
            .body("appManager.url", equalTo("https://app-manager.test/"))
            .body("appManager.oin", equalTo(BerichtenAppConfig.DEMO_APP_MANAGER_OIN))
    }

    @Test
    fun `de scripts en de meegeleverde jose komen als JavaScript van deze origin`() {
        listOf("/js/app.js", "/js/activeren.js", "/js/sleutelkluis.js", "/js/verloop.js", "/js/vangnet.js", "/vendor/jose/index.js").forEach { pad ->
            given().get(pad).then().statusCode(200).contentType(containsString("javascript"))
        }
    }
}
