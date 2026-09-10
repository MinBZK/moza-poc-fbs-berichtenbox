package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BijlageContentTypeFilterTest {

    private val filter = BijlageContentTypeFilter()

    private fun run(property: Any?, naam: Any? = null, status: Int = 200): MultivaluedHashMap<String, Any> {
        val req = mockk<ContainerRequestContext>()
        val res = mockk<ContainerResponseContext>()
        val headers = MultivaluedHashMap<String, Any>()
        headers.add("Content-Type", "application/octet-stream")
        every { req.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) } returns property
        every { req.getProperty(BIJLAGE_NAAM_PROPERTY) } returns naam
        every { res.headers } returns headers
        every { res.status } returns status
        filter.filter(req, res)
        return headers
    }

    // 199 en 300 zijn de grenswaarden: zonder die twee glipt een `200..300`-typefout in de
    // range er ongemerkt doorheen. De rest is wat dit endpoint werkelijk kan opleveren.
    @ParameterizedTest
    @ValueSource(ints = [199, 300, 301, 400, 403, 404, 406, 409, 500, 503])
    fun `een niet-geslaagde response blijft ongemoeid`(status: Int) {
        val headers = run("application/pdf", naam = "aanslag.pdf", status = status)

        assertEquals("application/octet-stream", headers.getFirst("Content-Type"))
        assertEquals(null, headers.getFirst("Content-Disposition"))
    }

    // De grens loopt op de hele 2xx-reeks en niet op "precies 200": response-filters dragen
    // geen `@Priority`, dus de volgorde t.o.v. een filter dat de status nog verzet ligt niet
    // vast. 206 is bovendien wat range-support zou opleveren.
    @ParameterizedTest
    @ValueSource(ints = [200, 206, 299])
    fun `elke geslaagde status krijgt het type en de dispositie wel`(status: Int) {
        val headers = run("application/pdf", status = status)

        assertEquals("application/pdf", headers.getFirst("Content-Type"))
        assertEquals("inline", headers.getFirst("Content-Disposition"))
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
        // een type dat we niet begrijpen tonen we niet — de naam mag wel mee, die is
        // gesaneerd los van het type.
        val headers = run("not a valid media type\r\nX-Injected: yes", "nota.pdf")
        assertEquals("application/octet-stream", headers.getFirst("Content-Type"))
        assertEquals(
            "attachment; filename=\"nota.pdf\"; filename*=UTF-8''nota.pdf",
            headers.getFirst("Content-Disposition"),
        )
    }

    @Test
    fun `een MIME-type met control-tekens in een parameter wordt niet doorgelaten`() {
        // Zo'n waarde parseert wel, maar de HTTP-laag weigert de header pas bij het
        // schrijven van de response — dan is de bijlage onophaalbaar zonder uitleg.
        val headers = run("application/pdf;name=\"a\r\nX-Injected: 1\"", "nota.pdf")
        assertEquals("application/octet-stream", headers.getFirst("Content-Type"))
        assertEquals(
            "attachment; filename=\"nota.pdf\"; filename*=UTF-8''nota.pdf",
            headers.getFirst("Content-Disposition"),
        )
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
