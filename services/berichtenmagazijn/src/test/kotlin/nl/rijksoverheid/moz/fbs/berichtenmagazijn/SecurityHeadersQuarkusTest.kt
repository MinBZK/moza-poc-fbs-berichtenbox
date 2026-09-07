package nl.rijksoverheid.moz.fbs.berichtenmagazijn

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Dezelfde toets als aan de uitvraag-kant. Apart en niet alleen daar, omdat het magazijn
 * zijn Swagger UI ook in productie aan heeft staan (`quarkus.swagger-ui.always-include`):
 * juist hier moet de losse CSP op het beheerpad blijven staan.
 */
@QuarkusTest
class SecurityHeadersQuarkusTest {

    private val strikteCsp = "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    private fun headerWaarden(pad: String, naam: String): List<String> =
        given()
            .`when`()
            .get(pad)
            .then()
            .extract()
            .headers()
            .getValues(naam)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Strict-Transport-Security",
            "X-Frame-Options",
            "X-Content-Type-Options",
            "Content-Security-Policy",
            "Referrer-Policy",
            "Cache-Control",
        ],
    )
    fun `elke security-header staat precies een keer op een API-response`(naam: String) {
        val waarden = headerWaarden("/api/v1/berichten", naam)

        assertEquals(1, waarden.size) { "$naam kwam ${waarden.size}x voorbij: $waarden" }
    }

    @Test
    fun `een API-response draagt de strenge CSP`() {
        assertEquals(listOf(strikteCsp), headerWaarden("/api/v1/berichten", "Content-Security-Policy"))
    }

    @Test
    fun `het beheerpad houdt de losse CSP zodat Swagger UI blijft werken`() {
        assertEquals(listOf("frame-ancestors 'none'"), headerWaarden("/q/health", "Content-Security-Policy"))
        assertEquals(1, headerWaarden("/q/health", "X-Frame-Options").size)
    }

    // De hele rechtvaardiging van de losse CSP is dat Swagger UI een HTML-pagina is die
    // zijn eigen script en stylesheet laadt — hier ook in productie aan.
    @Test
    fun `een Swagger UI-asset houdt de losse CSP`() {
        assertEquals(
            listOf("frame-ancestors 'none'"),
            headerWaarden("/q/swagger-ui/index.html", "Content-Security-Policy"),
        )
    }

    // `/openapi.json` staat bewust op de root en niet onder /q, dus het valt aan de
    // strenge kant van de grens. Het is een JSON-document, dus dat kan.
    @Test
    fun `de OpenAPI-spec valt aan de strenge kant van de grens`() {
        assertEquals(listOf(strikteCsp), headerWaarden("/openapi.json", "Content-Security-Policy"))
    }

    // Een onbekend pad is de enige situatie waarin géén applicatie-route draait. Een
    // verkeerd geregistreerd filter valt hier als eerste door de mand.
    @Test
    fun `een 404 op een onbekend pad draagt de headers ook`() {
        assertEquals(listOf("DENY"), headerWaarden("/bestaat-niet", "X-Frame-Options"))
        assertEquals(listOf(strikteCsp), headerWaarden("/bestaat-niet", "Content-Security-Policy"))
    }

    // Het onderscheid dat de andere tests niet kunnen maken: zolang de registratie de
    // enige schrijver is, levert toevoegen dezelfde response als vervangen. Hier schrijft
    // de resource eerst zelf, dus alleen vervangen geeft één waarde — en de onze.
    @Test
    fun `een header die de resource zelf zette wordt vervangen, niet aangevuld`() {
        assertEquals(listOf("DENY"), headerWaarden("/api/v1/test-only/eigen-headers", "X-Frame-Options"))
        assertEquals(listOf(strikteCsp), headerWaarden("/api/v1/test-only/eigen-headers", "Content-Security-Policy"))
    }

    // Cache-Control is de uitzondering: een endpoint dat bewust cacheable is houdt zijn
    // eigen waarde. `no-store` eroverheen zetten zou die keuze stil ongedaan maken.
    @Test
    fun `een eigen Cache-Control van de resource blijft end-to-end staan`() {
        assertEquals(listOf("max-age=60"), headerWaarden("/api/v1/test-only/eigen-headers", "Cache-Control"))
    }

    @Test
    fun `de overige headers hebben op elk pad dezelfde waarde`() {
        listOf("/api/v1/berichten", "/q/health", "/openapi.json").forEach { pad ->
            assertEquals(listOf("DENY"), headerWaarden(pad, "X-Frame-Options"), pad)
            assertEquals(listOf("nosniff"), headerWaarden(pad, "X-Content-Type-Options"), pad)
            assertEquals(listOf("no-referrer"), headerWaarden(pad, "Referrer-Policy"), pad)
            assertEquals(listOf("no-store"), headerWaarden(pad, "Cache-Control"), pad)
        }
    }
}
