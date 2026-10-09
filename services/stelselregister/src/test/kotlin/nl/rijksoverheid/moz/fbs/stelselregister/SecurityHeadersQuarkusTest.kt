package nl.rijksoverheid.moz.fbs.stelselregister

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Dezelfde toets als bij de andere diensten, hier ook over de paden die buiten JAX-RS vallen:
 * de `/.well-known`-routes staan rechtstreeks op de router en moeten de headers net zo dragen.
 */
@QuarkusTest
class SecurityHeadersQuarkusTest {

    private val strikteCsp = "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    private fun headerWaarden(pad: String, naam: String): List<String> =
        given().redirects().follow(false).`when`().get(pad).then().extract().headers().getValues(naam)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/api/v1/stelseldocument",
            "/.well-known/jwks.json",
            "/.well-known/security.txt",
            "/openapi.json",
            "/bestaat-niet",
        ],
    )
    fun `elk publiek pad draagt elke security-header precies een keer, met de strenge CSP`(pad: String) {
        listOf(
            "Strict-Transport-Security",
            "X-Frame-Options",
            "X-Content-Type-Options",
            "Content-Security-Policy",
            "Referrer-Policy",
            "Cache-Control",
        ).forEach { naam ->
            assertEquals(1, headerWaarden(pad, naam).size) { "$naam op $pad: ${headerWaarden(pad, naam)}" }
        }

        assertEquals(listOf(strikteCsp), headerWaarden(pad, "Content-Security-Policy"), pad)
        assertEquals(listOf("DENY"), headerWaarden(pad, "X-Frame-Options"), pad)
        assertEquals(listOf("nosniff"), headerWaarden(pad, "X-Content-Type-Options"), pad)
    }

    @Test
    fun `het beheerpad houdt de losse CSP`() {
        assertEquals(listOf("frame-ancestors 'none'"), headerWaarden("/q/health", "Content-Security-Policy"))
    }

    // Het document en de sleutelset zijn bewust cacheable; al het andere blijft `no-store`.
    @Test
    fun `alleen het document en de sleutelset wijken af van no-store`() {
        assertEquals(listOf("public, max-age=300"), headerWaarden("/api/v1/stelseldocument", "Cache-Control"))
        assertEquals(listOf("public, max-age=3600"), headerWaarden("/.well-known/jwks.json", "Cache-Control"))
        assertEquals(listOf("no-store"), headerWaarden("/openapi.json", "Cache-Control"))
        assertEquals(listOf("no-store"), headerWaarden("/q/health", "Cache-Control"))
        assertEquals(listOf("no-store"), headerWaarden("/bestaat-niet", "Cache-Control"))
    }

    @Test
    fun `een header die een resource zelf zette wordt vervangen, niet aangevuld`() {
        assertEquals(listOf("DENY"), headerWaarden("/api/v1/test-only/eigen-headers", "X-Frame-Options"))
        assertEquals(listOf(strikteCsp), headerWaarden("/api/v1/test-only/eigen-headers", "Content-Security-Policy"))
        assertEquals(listOf("max-age=60"), headerWaarden("/api/v1/test-only/eigen-headers", "Cache-Control"))
    }
}
