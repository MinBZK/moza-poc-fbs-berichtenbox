package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class DownstreamResultaatTest {

    @Test
    fun `HttpFout 500 is herstelbaar`() {
        val r = DownstreamResultaat.HttpFout(statusCode = 500, retryAfter = null, reden = "HTTP 500")
        assertTrue(r.herstelbaar)
    }

    @Test
    fun `HttpFout 408 en 429 herstelbaar`() {
        assertTrue(DownstreamResultaat.HttpFout(408, null, "Request timeout").herstelbaar)
        assertTrue(DownstreamResultaat.HttpFout(429, null, "Too many requests").herstelbaar)
    }

    @Test
    fun `HttpFout 400 niet herstelbaar`() {
        assertFalse(DownstreamResultaat.HttpFout(400, null, "Bad request").herstelbaar)
    }

    @Test
    fun `HttpFout 401 403 404 niet herstelbaar`() {
        assertFalse(DownstreamResultaat.HttpFout(401, null, "X").herstelbaar)
        assertFalse(DownstreamResultaat.HttpFout(403, null, "X").herstelbaar)
        assertFalse(DownstreamResultaat.HttpFout(404, null, "X").herstelbaar)
    }

    @Test
    fun `HttpFout met Retry-After geeft die door`() {
        val r = DownstreamResultaat.HttpFout(503, Duration.ofSeconds(60), "rate-limited")
        assertEquals(Duration.ofSeconds(60), r.retryAfter)
    }

    @Test
    fun `Timeout altijd herstelbaar`() {
        val r = DownstreamResultaat.Timeout("read timeout")
        assertTrue(r.herstelbaar)
        assertNull(r.retryAfter)
        assertEquals("read timeout", r.reden)
    }

    @Test
    fun `NetwerkFout altijd herstelbaar`() {
        val r = DownstreamResultaat.NetwerkFout("connection reset")
        assertTrue(r.herstelbaar)
    }

    @Test
    fun `SerialisatieFout niet herstelbaar`() {
        val r = DownstreamResultaat.SerialisatieFout("kapotte json")
        assertFalse(r.herstelbaar)
    }

    @Test
    fun `ConfiguratieFout niet herstelbaar`() {
        val r = DownstreamResultaat.ConfiguratieFout("ongeldige url")
        assertFalse(r.herstelbaar)
    }
}
