package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BijlageContentTypeFilterTest {

    private val filter = BijlageContentTypeFilter()

    private fun run(property: Any?): MultivaluedHashMap<String, Any> {
        val req = mockk<ContainerRequestContext>()
        val res = mockk<ContainerResponseContext>()
        val headers = MultivaluedHashMap<String, Any>()
        headers.add("Content-Type", "application/octet-stream")
        every { req.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) } returns property
        every { res.headers } returns headers
        filter.filter(req, res)
        return headers
    }

    @Test
    fun `met MIME-type op request - filter overschrijft Content-Type`() {
        val headers = run("application/pdf")
        assertEquals("application/pdf", headers.getFirst("Content-Type"))
    }

    @Test
    fun `zonder MIME-type op request - Content-Type blijft ongewijzigd`() {
        val headers = run(null)
        assertEquals("application/octet-stream", headers.getFirst("Content-Type"))
    }

    @Test
    fun `MIME-type van niet-String type wordt genegeerd`() {
        val headers = run(42)
        assertEquals("application/octet-stream", headers.getFirst("Content-Type"))
    }

    @Test
    fun `ongeldige MediaType-string (defense-in-depth) wordt genegeerd`() {
        // De resource zou dit normaal moeten vangen, maar als een toekomstige caller de
        // property zou zetten zonder validatie, mag het filter geen header-splitting
        // toestaan via bv. \r\n in de waarde. De default Content-Type blijft staan.
        val headers = run("not a valid media type\r\nX-Injected: yes")
        assertEquals("application/octet-stream", headers.getFirst("Content-Type"))
    }
}
