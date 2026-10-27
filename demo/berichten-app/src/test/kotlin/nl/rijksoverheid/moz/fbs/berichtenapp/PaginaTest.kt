package nl.rijksoverheid.moz.fbs.berichtenapp

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

@QuarkusTest
class PaginaTest {

    @Test
    fun `de pagina komt met een CSP die alleen eigen script toestaat en alleen de App Manager als bestemming`() {
        val csp = given().get("/").then().statusCode(200)
            .header("Referrer-Policy", "no-referrer")
            .header("X-Content-Type-Options", "nosniff")
            .header("X-Frame-Options", "DENY")
            .extract().header("Content-Security-Policy")
        val richtlijnen = csp.split(';').map(String::trim).associate { it.substringBefore(' ') to it.substringAfter(' ', "") }

        assertEquals("'self'", richtlijnen["script-src"])
        assertEquals("'self' https://app-manager.test/", richtlijnen["connect-src"])
        assertEquals("'none'", richtlijnen["default-src"])
        assertEquals("'none'", richtlijnen["frame-ancestors"])
        assertEquals("'none'", richtlijnen["form-action"])
        assertEquals("'script'", richtlijnen["require-trusted-types-for"])
    }

    @Test
    fun `de pagina heeft geen inline script, geen inline stijl en geen event-handlers`() {
        val html = given().get("/").then().statusCode(200).extract().asString()

        assertFalse(Regex("<script(?![^>]*\\bsrc=)").containsMatchIn(html), "inline script")
        assertFalse(Regex("<style|\\sstyle=|\\son[a-z]+=", RegexOption.IGNORE_CASE).containsMatchIn(html), "inline stijl of handler")
    }

    @Test
    fun `de configuratie noemt adres en OIN van de App Manager`() {
        given().get("/config.json").then().statusCode(200)
            .body("appManager.url", equalTo("https://app-manager.test/"))
            .body("appManager.oin", equalTo(BerichtenAppConfig.DEMO_APP_MANAGER_OIN))
    }

    @Test
    fun `de scripts en de meegeleverde jose komen als JavaScript van deze origin`() {
        listOf("/js/app.js", "/js/activeren.js", "/js/sleutelkluis.js", "/vendor/jose-6.2.13/index.js").forEach { pad ->
            given().get(pad).then().statusCode(200).contentType(containsString("javascript"))
        }
    }
}
