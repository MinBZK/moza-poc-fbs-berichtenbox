package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import org.jboss.logging.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Pint de allowlist-invariant van [isUpstreamStoring]/[mapUpstreamFout]: alleen
 * een echte 4xx propageert 1-op-1; transport-fouten en elke non-4xx worden 502.
 */
class UpstreamFaultTest {

    private val log: Logger = Logger.getLogger(UpstreamFaultTest::class.java)

    @Test
    fun `een echte 4xx is geen upstream-storing en propageert`() {
        assertFalse(isUpstreamStoring(WebApplicationException("niet gevonden", 404)))

        val ex = assertThrows<WebApplicationException> {
            mapUpstreamFout(log, "test") { throw WebApplicationException("conflict", 409) }
        }

        assertEquals(409, ex.response.status)
    }

    @Test
    fun `een 5xx is een upstream-storing en wordt 502`() {
        assertTrue(isUpstreamStoring(WebApplicationException("down", 503)))

        val ex = assertThrows<WebApplicationException> {
            mapUpstreamFout(log, "test") { throw WebApplicationException("down", 503) }
        }

        assertEquals(502, ex.response.status)
    }

    @Test
    fun `een WAE zonder response telt als transport-storing en wordt 502`() {
        val zonderResponse = io.mockk.mockk<WebApplicationException>(relaxed = true) {
            io.mockk.every { response } returns null
        }

        assertTrue(isUpstreamStoring(zonderResponse))
    }

    @Test
    fun `een ProcessingException wordt 502`() {
        val ex = assertThrows<WebApplicationException> {
            mapUpstreamFout(log, "test") { throw ProcessingException("connect timeout") }
        }

        assertEquals(502, ex.response.status)
    }

    @Test
    fun `een onverwachte non-4xx-status (3xx) telt als storing`() {
        assertTrue(isUpstreamStoring(WebApplicationException("redirect?", 302)))
    }
}
