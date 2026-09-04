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

    private fun run(property: Any?, naam: Any? = null): MultivaluedHashMap<String, Any> {
        val req = mockk<ContainerRequestContext>()
        val res = mockk<ContainerResponseContext>()
        val headers = MultivaluedHashMap<String, Any>()
        headers.add("Content-Type", "application/octet-stream")
        every { req.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) } returns property
        every { req.getProperty(BIJLAGE_NAAM_PROPERTY) } returns naam
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
        assertEquals(null, headers.getFirst("Content-Disposition"))
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
        // toestaan via bv. \r\n in de waarde. De default Content-Type blijft staan, en
        // een type dat we niet begrijpen tonen we niet.
        val headers = run("not a valid media type\r\nX-Injected: yes", "nota.pdf")
        assertEquals("application/octet-stream", headers.getFirst("Content-Type"))
        assertEquals("attachment", headers.getFirst("Content-Disposition"))
    }

    @Test
    fun `een PDF mag getoond worden en draagt de bestandsnaam`() {
        val headers = run("application/pdf", "aanslag 2026.pdf")
        assertEquals(
            "inline; filename=\"aanslag_2026.pdf\"; filename*=UTF-8''aanslag%202026.pdf",
            headers.getFirst("Content-Disposition"),
        )
    }

    @Test
    fun `een in de browser uitvoerbaar type blijft een download`() {
        val headers = run("text/html", "kwaad.html")
        assertEquals(
            "attachment; filename=\"kwaad.html\"; filename*=UTF-8''kwaad.html",
            headers.getFirst("Content-Disposition"),
        )
    }

    @Test
    fun `zonder naam draagt de header alleen de dispositie`() {
        val headers = run("application/pdf")
        assertEquals("inline", headers.getFirst("Content-Disposition"))
    }

    @Test
    fun `een naam van een niet-String type wordt genegeerd`() {
        val headers = run("application/pdf", 42)
        assertEquals("inline", headers.getFirst("Content-Disposition"))
    }
}
