package nl.rijksoverheid.moz.fbs.common

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CacheControlFilterTest {

    private val filter = CacheControlFilter()

    private fun runWithHeaders(initial: Map<String, String>): MultivaluedHashMap<String, Any> {
        val req = mockk<ContainerRequestContext>()
        val res = mockk<ContainerResponseContext>()
        val headers = MultivaluedHashMap<String, Any>()
        initial.forEach { (k, v) -> headers.add(k, v) }
        every { res.headers } returns headers
        filter.filter(req, res)
        return headers
    }

    @Test
    fun `geen Cache-Control aanwezig - filter zet no-store`() {
        val headers = runWithHeaders(emptyMap())
        assertEquals("no-store", headers.getFirst("Cache-Control"))
    }

    @Test
    fun `bestaande Cache-Control blijft ongewijzigd`() {
        val headers = runWithHeaders(mapOf("Cache-Control" to "public, max-age=60"))
        assertEquals("public, max-age=60", headers.getFirst("Cache-Control"))
    }
}
