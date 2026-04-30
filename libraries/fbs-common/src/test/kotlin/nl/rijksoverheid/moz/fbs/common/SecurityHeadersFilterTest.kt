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

    // Exacte waarden — internet.nl test op deze strings.
    private val expected = mapOf(
        "Strict-Transport-Security" to "max-age=31536000; includeSubDomains; preload",
        "X-Frame-Options" to "DENY",
        "X-Content-Type-Options" to "nosniff",
        "Content-Security-Policy" to "frame-ancestors 'none'",
        "Referrer-Policy" to "no-referrer",
    )

    private fun runFilter(initial: Map<String, String> = emptyMap()): MultivaluedHashMap<String, Any> {
        val req = mockk<ContainerRequestContext>()
        val res = mockk<ContainerResponseContext>()
        val headers = MultivaluedHashMap<String, Any>()
        initial.forEach { (k, v) -> headers.add(k, v) }
        every { res.headers } returns headers
        filter.filter(req, res)
        return headers
    }

    private fun assertExpectedHeaders(headers: MultivaluedHashMap<String, Any>) {
        expected.forEach { (name, value) ->
            assertEquals(listOf(value), headers[name]) {
                "$name: enkele waarde verwacht ($value), kreeg ${headers[name]}"
            }
        }
    }

    @Test
    fun `zet alle vereiste security-headers met exacte waarden`() {
        assertExpectedHeaders(runFilter())
    }

    @Test
    fun `bestaande security-header-waarden worden vervangen door de filter-waarden`() {
        // Vooraf vullen met bewust afwijkende waarden simuleert een eerder filter of
        // resource-level header die nooit naar de client mag lekken: het security-filter
        // moet ze allemaal overschrijven, zonder duplicaten te creëren.
        val initial = expected.mapValues { "OUDE_${it.key}_WAARDE" }
        assertExpectedHeaders(runFilter(initial))
    }
}
