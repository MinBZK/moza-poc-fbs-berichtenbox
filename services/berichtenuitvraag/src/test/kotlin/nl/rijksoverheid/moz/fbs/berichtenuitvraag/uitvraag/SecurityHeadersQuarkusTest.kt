package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Toetst op de draad wat `fbs-common/SecurityHeadersRegistratie` belooft. Een pure test
 * kan dat niet: de duplicatie die deze constructie oplost ontstond juist in het samenspel
 * van de HTTP-laag en RESTEasy, en dat samenspel bestaat alleen in een draaiende dienst.
 */
@QuarkusTest
@TestProfile(MockSessiecacheProfile::class)
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

    // De regressie waar het om begonnen is: eerder stond elke header er twee keer.
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

    @ParameterizedTest
    @ValueSource(strings = ["Strict-Transport-Security", "X-Frame-Options", "Content-Security-Policy", "Cache-Control"])
    fun `ook een beheerpad krijgt elke header precies een keer`(naam: String) {
        val waarden = headerWaarden("/q/health", naam)

        assertEquals(1, waarden.size) { "$naam kwam ${waarden.size}x voorbij: $waarden" }
    }

    @Test
    fun `een API-response draagt de strenge CSP`() {
        assertEquals(listOf(strikteCsp), headerWaarden("/api/v1/berichten", "Content-Security-Policy"))
    }

    // `/openapi.json` staat bewust op de root en niet onder /q, dus het valt aan de
    // strenge kant van de grens. Het is een JSON-document, dus dat kan.
    @Test
    fun `de OpenAPI-spec valt aan de strenge kant van de grens`() {
        assertEquals(listOf(strikteCsp), headerWaarden("/openapi.json", "Content-Security-Policy"))
    }

    // Swagger UI en de dev-UI laden hun eigen script en stylesheet; `default-src 'none'`
    // zou die pagina's breken. De clickjacking-bescherming blijft er wel op staan.
    @Test
    fun `een beheerpad houdt de losse CSP zodat Swagger UI blijft werken`() {
        assertEquals(listOf("frame-ancestors 'none'"), headerWaarden("/q/health", "Content-Security-Policy"))
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

    // Een onbekend pad is de enige situatie waarin géén applicatie-route draait. Een
    // verkeerd geregistreerd filter valt hier als eerste door de mand.
    @Test
    fun `een 404 op een onbekend pad draagt de headers ook`() {
        assertEquals(listOf("DENY"), headerWaarden("/bestaat-niet", "X-Frame-Options"))
        assertEquals(listOf(strikteCsp), headerWaarden("/bestaat-niet", "Content-Security-Policy"))
    }

    // De hele rechtvaardiging van de losse CSP is dat Swagger UI een HTML-pagina is die
    // zijn eigen script en stylesheet laadt. `/q/health` is JSON en bewijst dat niet.
    @Test
    fun `een Swagger UI-asset houdt de losse CSP`() {
        assertEquals(
            listOf("frame-ancestors 'none'"),
            headerWaarden("/q/swagger-ui/index.html", "Content-Security-Policy"),
        )
    }

    // Een gestreamde response commit zijn headers op een ander moment dan een gebufferde;
    // dat is precies waar een `headersEndHandler` anders zou kunnen uitpakken.
    @Test
    fun `een SSE-stream draagt de headers net zo goed`() {
        val headers = given()
            .header("X-Ontvanger", "BSN:999990019")
            .header("Accept", "text/event-stream")
            .`when`()
            .get("/api/v1/berichten/_ophalen")
            .then()
            .extract()
            .headers()

        assertEquals(listOf("DENY"), headers.getValues("X-Frame-Options"))
        assertEquals(listOf(strikteCsp), headers.getValues("Content-Security-Policy"))
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
