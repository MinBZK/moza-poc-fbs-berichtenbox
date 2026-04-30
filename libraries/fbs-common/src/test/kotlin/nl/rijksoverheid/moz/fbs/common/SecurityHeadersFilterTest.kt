package nl.rijksoverheid.moz.fbs.common

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SecurityHeadersFilterTest {

    private val filter = SecurityHeadersFilter()

    private fun apply(): MultivaluedHashMap<String, Any> {
        val req = mockk<ContainerRequestContext>()
        val res = mockk<ContainerResponseContext>()
        val headers = MultivaluedHashMap<String, Any>()
        every { res.headers } returns headers
        filter.filter(req, res)
        return headers
    }

    @Test
    fun `zet alle vereiste security-headers met exacte waarden`() {
        // Exacte waarden — internet.nl test op deze strings.
        val headers = apply()
        assertEquals(
            "max-age=31536000; includeSubDomains; preload",
            headers.getFirst("Strict-Transport-Security"),
        )
        assertEquals("DENY", headers.getFirst("X-Frame-Options"))
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"))
        assertEquals("frame-ancestors 'none'", headers.getFirst("Content-Security-Policy"))
        assertEquals("no-referrer", headers.getFirst("Referrer-Policy"))
    }

    @Test
    fun `tweede call overschrijft, geen duplicate headers`() {
        val req = mockk<ContainerRequestContext>()
        val res = mockk<ContainerResponseContext>()
        val headers = MultivaluedHashMap<String, Any>()
        every { res.headers } returns headers
        filter.filter(req, res)
        filter.filter(req, res)
        assertEquals(1, headers.getValue("Strict-Transport-Security").size)
        assertEquals(1, headers.getValue("X-Frame-Options").size)
    }
}
