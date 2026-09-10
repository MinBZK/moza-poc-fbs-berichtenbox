package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * De simulator hoort zich hier hetzelfde te gedragen als het echte magazijn, anders is hij
 * van buitenaf te herkennen aan zijn response-headers. Deze tests spiegelen daarom
 * `…berichtenmagazijn.ophaal.BijlageContentTypeFilterTest`.
 */
class BijlageContentTypeFilterTest {

    private val filter = BijlageContentTypeFilter()

    private class Uitkomst(val headers: MultivaluedHashMap<String, Any>, val status: Int, val entity: Any?)

    private fun run(property: Any?, naam: Any? = null, status: Int = 200): Uitkomst {
        val req = mockk<ContainerRequestContext>()
        val res = mockk<ContainerResponseContext>(relaxed = true)
        val headers = MultivaluedHashMap<String, Any>()
        headers.add("Content-Type", "application/octet-stream")

        var gezetteStatus = status
        var gezetteEntity: Any? = null

        every { req.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) } returns property
        every { req.getProperty(BIJLAGE_NAAM_PROPERTY) } returns naam
        every { res.headers } returns headers
        every { res.status } returns status
        every { res.status = any() } answers { gezetteStatus = firstArg() }
        every { res.entity = any() } answers { gezetteEntity = firstArg() }

        filter.filter(req, res)

        return Uitkomst(headers, gezetteStatus, gezetteEntity)
    }

    // 199 en 300 zijn de grenswaarden: zonder die twee glipt een `200..300`-typefout in de
    // range er ongemerkt doorheen.
    @ParameterizedTest
    @ValueSource(ints = [199, 300, 301, 400, 403, 404, 406, 409, 500, 503])
    fun `een niet-geslaagde response blijft ongemoeid`(status: Int) {
        val uitkomst = run("application/pdf", naam = "aanslag.pdf", status = status)

        assertEquals("application/octet-stream", uitkomst.headers.getFirst("Content-Type"))
        assertEquals(null, uitkomst.headers.getFirst("Content-Disposition"))
    }

    @ParameterizedTest
    @ValueSource(ints = [200, 206, 299])
    fun `elke geslaagde status krijgt het type en de dispositie wel`(status: Int) {
        val uitkomst = run("application/pdf", status = status)

        assertEquals("application/pdf", uitkomst.headers.getFirst("Content-Type"))
        assertEquals("inline", uitkomst.headers.getFirst("Content-Disposition"))
    }

    // De 500-tak bestaat om te voorkomen dat bytes onder een verkeerd type de deur uit gaan.
    // Op een response die al een fout is, zijn er geen bytes en is er al een correlatie-id;
    // er een tweede 500 overheen zetten zou de oorspronkelijke storing onvindbaar maken.
    @Test
    fun `een onparsebaar type op een al mislukte response levert geen tweede fout op`() {
        val uitkomst = run("not-a-mime-type", status = 503)

        assertEquals(503, uitkomst.status)
        assertEquals(null, uitkomst.entity)
        assertEquals("application/octet-stream", uitkomst.headers.getFirst("Content-Type"))
    }

    @Test
    fun `een onparsebaar type op een geslaagde response levert wel een 500 op`() {
        val uitkomst = run("not-a-mime-type")

        assertEquals(500, uitkomst.status)
        assertEquals("application/problem+json", uitkomst.headers.getFirst("Content-Type").toString())
    }

    @Test
    fun `zonder MIME-type op de request doet het filter niets`() {
        val uitkomst = run(null)

        assertEquals("application/octet-stream", uitkomst.headers.getFirst("Content-Type"))
        assertEquals(null, uitkomst.headers.getFirst("Content-Disposition"))
    }

    @Test
    fun `een in de browser uitvoerbaar type blijft een download`() {
        val uitkomst = run("text/html", "kwaad.html")

        assertEquals(
            "attachment; filename=\"kwaad.html\"; filename*=UTF-8''kwaad.html",
            uitkomst.headers.getFirst("Content-Disposition"),
        )
    }
}
