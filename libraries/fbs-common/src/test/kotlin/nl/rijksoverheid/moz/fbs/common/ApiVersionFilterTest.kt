package nl.rijksoverheid.moz.fbs.common

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApiVersionFilterTest {

    private fun runFilter(provided: String, existing: List<String> = emptyList()): String? {
        val provider = mockk<ApiVersionProvider> { every { version() } returns provided }
        val filter = ApiVersionFilter(provider)
        val req = mockk<ContainerRequestContext>()
        val res = mockk<ContainerResponseContext>()
        val headers = MultivaluedHashMap<String, Any>()
        existing.forEach { headers.add("API-Version", it) }
        every { res.headers } returns headers
        filter.filter(req, res)
        return headers.getFirst("API-Version") as String?
    }

    @Test
    fun `zet API-Version op de waarde van de provider`() {
        assertEquals("v1", runFilter(provided = "v1"))
    }

    @Test
    fun `bestaande API-Version-waarden worden vervangen, niet aangevuld`() {
        // putSingle moet legacy/dubbele waarden weghalen — anders krijgt de client
        // twee API-Version-headers die als CSV gemerged worden.
        val provider = mockk<ApiVersionProvider> { every { version() } returns "v2" }
        val filter = ApiVersionFilter(provider)
        val req = mockk<ContainerRequestContext>()
        val res = mockk<ContainerResponseContext>()
        val headers = MultivaluedHashMap<String, Any>()
        headers.add("API-Version", "v1")
        headers.add("API-Version", "v0")
        every { res.headers } returns headers
        filter.filter(req, res)
        assertEquals(listOf("v2"), headers["API-Version"])
    }
}
