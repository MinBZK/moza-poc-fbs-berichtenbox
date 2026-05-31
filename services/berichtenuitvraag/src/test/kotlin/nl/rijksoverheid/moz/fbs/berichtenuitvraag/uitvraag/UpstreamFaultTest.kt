package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Unit-test van de SSE-fout-classificatie. De wire-status van een SSE-respons ligt
 * vast op 200 zodra de stream is opgezet, dus de allowlist-invariant kan niet
 * end-to-end via de HTTP-status worden geverifieerd — dat doen we hier op het
 * resultaat van [ssePreStreamFout] zelf (zie de KDoc bij die functie).
 */
class UpstreamFaultTest {

    @Test
    fun `echte 4xx behoudt status en wordt niet hertyped`() {
        val origineel = WebApplicationException("conflict", Response.Status.CONFLICT)

        val resultaat = ssePreStreamFout(origineel)

        // Status-behoudend: exact dezelfde exception terug (geen 502-her-wrap).
        assertSame(origineel, resultaat)
    }

    @Test
    fun `upstream-5xx wordt 502`() {
        val resultaat = ssePreStreamFout(WebApplicationException("down", 503))

        assertEquals(502, (resultaat as WebApplicationException).response.status)
    }

    @Test
    fun `transport-fout (ProcessingException) wordt 502`() {
        val resultaat = ssePreStreamFout(ProcessingException("connect timeout"))

        assertEquals(502, (resultaat as WebApplicationException).response.status)
    }

    @Test
    fun `onverwachte non-WAE-fout wordt 502`() {
        val resultaat = ssePreStreamFout(IllegalStateException("pijplijn-bug"))

        assertEquals(502, (resultaat as WebApplicationException).response.status)
    }
}
