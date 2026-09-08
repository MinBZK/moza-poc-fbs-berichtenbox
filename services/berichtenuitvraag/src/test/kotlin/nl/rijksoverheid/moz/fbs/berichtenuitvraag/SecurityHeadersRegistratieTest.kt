package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.quarkus.vertx.http.runtime.filters.Filters
import io.vertx.core.Handler
import io.vertx.core.MultiMap
import io.vertx.core.http.HttpServerResponse
import io.vertx.ext.web.RoutingContext
import nl.rijksoverheid.moz.fbs.common.SecurityHeaders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** `addHeadersEndHandler` geeft een handler-id terug; welke waarde doet er niet toe. */
private const val HANDLER_ID = 1

class SecurityHeadersRegistratieTest {

    // Dezelfde vorm als Quarkus zelf aanlevert: een root-pad van `/` en een relatieve
    // beheerpad-root zonder leidende slash.
    private val registratie = SecurityHeadersRegistratie("/", "q")

    /**
     * Draait de registratie zoals Quarkus dat doet — observer, dan het route-filter, dan
     * de `headersEndHandler` — en geeft de headers terug die de response overhoudt.
     */
    private fun headersNaVerwerking(
        pad: String,
        aanwezig: Map<String, String> = emptyMap(),
        status: Int = 200,
    ): MultiMap {
        val headers = MultiMap.caseInsensitiveMultiMap()
        aanwezig.forEach { (naam, waarde) -> headers.set(naam, waarde) }

        val response = mockk<HttpServerResponse>()
        every { response.headers() } returns headers
        every { response.statusCode } returns status

        val context = mockk<RoutingContext>(relaxed = true)
        every { context.response() } returns response
        every { context.normalizedPath() } returns pad

        val eindHandler = slot<Handler<Void>>()
        every { context.addHeadersEndHandler(capture(eindHandler)) } returns HANDLER_ID

        val routeFilter = slot<Handler<RoutingContext>>()
        val filters = mockk<Filters>()
        every { filters.register(capture(routeFilter), any()) } returns filters

        registratie.registreer(filters)
        routeFilter.captured.handle(context)
        eindHandler.captured.handle(null)

        verify { context.next() }

        return headers
    }

    @Test
    fun `een API-response krijgt elke header precies een keer`() {
        val headers = headersNaVerwerking("/api/v1/berichten")

        SecurityHeaders.voorPad("/api/v1/berichten", "/q").forEach { (naam, waarde) ->
            assertEquals(listOf(waarde), headers.getAll(naam)) { "$naam stond er ${headers.getAll(naam).size}x op: ${headers.getAll(naam)}" }
        }
    }

    // De aanleiding voor deze hele constructie: eerder droeg elke response de headers
    // twee keer, doordat de HTTP-laag en een JAX-RS-filter allebei plaatsten. Een browser
    // doorsnijdt twee CSP-headers en houdt de strengste over, waardoor een bewust
    // versoepelde policy stil geen effect zou hebben.
    @Test
    fun `een header die er al stond wordt vervangen, niet aangevuld`() {
        val headers = headersNaVerwerking(
            "/api/v1/berichten",
            mapOf(
                SecurityHeaders.X_FRAME_OPTIONS to "SAMEORIGIN",
                SecurityHeaders.CONTENT_SECURITY_POLICY to "frame-ancestors 'self'",
            ),
        )

        assertEquals(listOf("DENY"), headers.getAll(SecurityHeaders.X_FRAME_OPTIONS))
        assertEquals(
            listOf("default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"),
            headers.getAll(SecurityHeaders.CONTENT_SECURITY_POLICY),
        )
    }

    @Test
    fun `een geslaagde inline-bijlage krijgt de versmalde frame-headers`() {
        val headers = headersNaVerwerking(
            "/api/v1/berichten/1/bijlagen/2",
            mapOf(SecurityHeaders.CONTENT_DISPOSITION to "inline; filename=\"aanslag.pdf\""),
        )

        assertEquals(listOf("SAMEORIGIN"), headers.getAll(SecurityHeaders.X_FRAME_OPTIONS))
    }

    // De dispositie staat op de response zodra de bytes opgehaald zijn, maar een fout die
    // daarná ontstaat — het logboek is in productie fail-closed en gooit ná de
    // resource-methode — levert een foutbody op die de dispositie nog steeds draagt. Zo'n
    // response hoort de versoepeling niet te erven.
    @ParameterizedTest
    @ValueSource(ints = [301, 400, 403, 404, 500, 503])
    fun `een foutresponse met een inline-dispositie erft de versoepeling niet`(status: Int) {
        val headers = headersNaVerwerking(
            "/api/v1/berichten/1/bijlagen/2",
            mapOf(SecurityHeaders.CONTENT_DISPOSITION to "inline; filename=\"aanslag.pdf\""),
            status = status,
        )

        assertEquals(listOf("DENY"), headers.getAll(SecurityHeaders.X_FRAME_OPTIONS))
    }

    @Test
    fun `een beheerpad krijgt de losse CSP`() {
        val headers = headersNaVerwerking("/q/health")

        assertEquals("frame-ancestors 'none'", headers.get(SecurityHeaders.CONTENT_SECURITY_POLICY))
    }

    @Test
    fun `zonder eigen Cache-Control valt de response terug op no-store`() {
        val headers = headersNaVerwerking("/api/v1/berichten")

        assertEquals(listOf("no-store"), headers.getAll(SecurityHeaders.CACHE_CONTROL))
    }

    // Een endpoint dat bewust cacheable is, mag dat blijven: `no-store` eroverheen zetten
    // zou die keuze stil ongedaan maken.
    @Test
    fun `een eigen Cache-Control van de resource blijft staan`() {
        val headers = headersNaVerwerking(
            "/api/v1/berichten",
            mapOf(SecurityHeaders.CACHE_CONTROL to "max-age=60"),
        )

        assertEquals(listOf("max-age=60"), headers.getAll(SecurityHeaders.CACHE_CONTROL))
    }

    // Zonder deze test zou "plaats de headers meteen" er net zo goed uitzien: de andere
    // tests draaien de eind-handler zelf aan. Dit legt vast dát het uitgesteld gebeurt,
    // want alles wat ná het filter nog schrijft zou anders overheen komen.
    @Test
    fun `het filter plaatst nog niets voordat de response wordt afgesloten`() {
        val headers = MultiMap.caseInsensitiveMultiMap()

        val response = mockk<HttpServerResponse>()
        every { response.headers() } returns headers

        val context = mockk<RoutingContext>(relaxed = true)
        every { context.response() } returns response
        every { context.normalizedPath() } returns "/api/v1/berichten"
        every { context.addHeadersEndHandler(any()) } returns HANDLER_ID

        val routeFilter = slot<Handler<RoutingContext>>()
        val filters = mockk<Filters>()
        every { filters.register(capture(routeFilter), any()) } returns filters

        registratie.registreer(filters)
        routeFilter.captured.handle(context)

        assertNull(headers.get(SecurityHeaders.X_FRAME_OPTIONS))
    }
}
