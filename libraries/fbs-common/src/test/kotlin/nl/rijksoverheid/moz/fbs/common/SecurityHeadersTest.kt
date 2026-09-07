package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class SecurityHeadersTest {

    private val strikt = "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    private fun csp(pad: String, root: String = "/q") =
        SecurityHeaders.voorPad(pad, root).getValue(SecurityHeaders.CONTENT_SECURITY_POLICY)

    // Exacte waarden: internet.nl en de NCSC-baseline toetsen op deze strings, dus een
    // wijziging hier moet een bewuste zijn en niet uit een refactor volgen.
    @Test
    fun `een API-pad krijgt de volledige baseline`() {
        val headers = SecurityHeaders.voorPad("/api/v1/berichten", "/q")

        assertEquals(
            mapOf(
                "Strict-Transport-Security" to "max-age=31536000; includeSubDomains; preload",
                "X-Frame-Options" to "DENY",
                "X-Content-Type-Options" to "nosniff",
                "Content-Security-Policy" to strikt,
                "Referrer-Policy" to "no-referrer",
            ),
            headers,
        )
    }

    @Test
    fun `Cache-Control zit niet in de vaste set, want die kan per endpoint verschillen`() {
        assertTrue(SecurityHeaders.CACHE_CONTROL !in SecurityHeaders.voorPad("/api/v1/berichten", "/q").keys)
        assertEquals("no-store", SecurityHeaders.CACHE_CONTROL_DEFAULT)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/q", "/q/health", "/q/swagger-ui/index.html", "/q/dev-ui/welcome"])
    fun `beheerpaden houden alleen de clickjacking-bescherming in de CSP`(pad: String) {
        assertEquals("frame-ancestors 'none'", csp(pad))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/berichten", "/openapi.json", "/", "/qux", "/q-beheer", "/quux/q/health"])
    fun `alles buiten het beheerpad krijgt de strenge CSP`(pad: String) {
        assertEquals(strikt, csp(pad))
    }

    // De segmentgrens is de hele reden dat dit geen startsWith is: `/qux` begint met `/q`
    // maar is een ander pad. Zonder de grens zou dat stilzwijgend de losse policy krijgen.
    @ParameterizedTest
    @CsvSource(
        "/beheer,      /beheer,          true",
        "/beheer/,     /beheer,          true",
        "/beheer/x,    /beheer,          true",
        "/beheerder,   /beheer,          false",
        "/q/health,    /q/,              true",
        "/api,         /q,               false",
    )
    fun `de beheerpad-grens loopt op een segment, niet op een prefix`(pad: String, root: String, verwachtBeheerpad: Boolean) {
        val verwacht = if (verwachtBeheerpad) "frame-ancestors 'none'" else strikt

        assertEquals(verwacht, csp(pad.trim(), root.trim()))
    }

    // Een lege of `/`-root zou anders élk pad als beheerpad aanmerken en de strenge CSP
    // overal uitschakelen — de gevaarlijkste denkbare uitkomst van deze functie.
    @ParameterizedTest
    @ValueSource(strings = ["", "/"])
    fun `een lege of root-only beheerpad zet de strenge CSP niet overal uit`(root: String) {
        assertEquals(strikt, csp("/api/v1/berichten", root))
        assertEquals(strikt, csp("/", root))
    }

    // Quarkus' eigen default voor `quarkus.http.non-application-root-path` is `q`, zónder
    // leidende slash. Zonder de omzetting hieronder vergelijk je `/q/health` met `q`, valt
    // elk beheerpad aan de strenge kant, en levert dat een lege Swagger UI op zonder dat
    // iets een fout meldt — precies de stille uitkomst waar geen enkele andere test op valt.
    @ParameterizedTest
    @CsvSource(
        "/,        q,         /q",
        "/,        /q,        /q",
        "'',       q,         /q",
        "/app,     q,         /app/q",
        "/app/,    q,         /app/q",
        "/app,     /q,        /q",
        "/,        q/,        /q",
        "/,        beheer,    /beheer",
    )
    fun `een relatieve beheerpad-root wordt absoluut gemaakt onder het root-pad`(rootPad: String, ruw: String, verwacht: String) {
        assertEquals(verwacht, SecurityHeaders.beheerpadRoot(rootPad.trim(), ruw.trim()))
    }

    // Een lege beheerpad-root mag nooit het root-pad zelf opleveren: `/app/` als root laat
    // élk pad eronder hangen, waarmee de hele API de losse policy krijgt. Dat is de
    // omgekeerde fout van een gemiste Swagger UI, en de gevaarlijke van de twee.
    @ParameterizedTest
    @CsvSource(
        "/app,   ''",
        "/app,   /",
        "/,      ''",
        "/,      /",
        "'',     ''",
    )
    fun `een lege beheerpad-root laat de hele API niet aan de losse kant vallen`(rootPad: String, ruw: String) {
        val root = SecurityHeaders.beheerpadRoot(rootPad.trim(), ruw.trim().trim('\''))

        assertEquals(strikt, csp("/app/api/v1/berichten", root))
        assertEquals(strikt, csp("/api/v1/berichten", root))
        assertEquals(strikt, csp("/", root))
    }

    @Test
    fun `de omgezette root maakt het beheerpad ook echt herkenbaar`() {
        val root = SecurityHeaders.beheerpadRoot("/", "q")

        assertEquals("frame-ancestors 'none'", csp("/q/health", root))
        assertEquals(strikt, csp("/api/v1/berichten", root))
    }

    @Test
    fun `de headernamen zijn de namen die de functie ook echt teruggeeft`() {
        val namen = SecurityHeaders.voorPad("/api/v1/berichten", "/q").keys

        assertEquals(
            setOf(
                SecurityHeaders.STRICT_TRANSPORT_SECURITY,
                SecurityHeaders.X_FRAME_OPTIONS,
                SecurityHeaders.X_CONTENT_TYPE_OPTIONS,
                SecurityHeaders.CONTENT_SECURITY_POLICY,
                SecurityHeaders.REFERRER_POLICY,
            ),
            namen,
        )
    }
}
