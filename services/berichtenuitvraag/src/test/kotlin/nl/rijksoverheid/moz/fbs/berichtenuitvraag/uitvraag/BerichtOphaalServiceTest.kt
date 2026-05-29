package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class BerichtOphaalServiceTest {

    private val sessiecache: SessiecacheClient = mockk()
    private val magazijn: MagazijnClient = mockk()
    private val router: MagazijnRouter = mockk {
        every { forMagazijn(any()) } returns magazijn
    }
    private val service = BerichtOphaalService(sessiecache, router)

    private fun stubBerichtLookup(berichtId: UUID, magazijnId: String = "magazijn-a") {
        val bericht = Bericht().apply {
            this.berichtId = berichtId
            this.magazijnId = magazijnId
            this.onderwerp = "X"
            this.publicatietijdstip = java.time.Instant.parse("2026-05-26T10:00:00Z")
        }
        every { sessiecache.bericht(any(), berichtId) } returns bericht
    }

    @Test
    fun `haalBericht delegeert naar sessiecache`() {
        val id = UUID.randomUUID()
        val bericht = Bericht().apply { berichtId = id }
        every { sessiecache.bericht("BSN:1", id) } returns bericht

        val result = service.haalBericht("BSN:1", id)

        assertEquals(id, result.berichtId)
    }

    @Test
    fun `haalBijlage retourneert mimeType en bytes uit magazijn-response`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val mockResp = mockk<Response> {
            every { status } returns 200
            every { readEntity(ByteArray::class.java) } returns bytes
            every { getHeaderString("Content-Type") } returns "application/pdf"
            every { close() } returns Unit
        }
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:1", berichtId, bijlageId) } returns mockResp

        val (mimeType, content) = service.haalBijlage("BSN:1", berichtId, bijlageId)

        assertEquals("application/pdf", mimeType)
        assertArrayEquals(bytes, content)
    }

    @Test
    fun `haalBijlage geeft raw Content-Type ook door als het ongeldig is (filter handelt fallback af)`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val bytes = byteArrayOf(1, 2, 3)
        val mockResp = mockk<Response> {
            every { status } returns 200
            every { readEntity(ByteArray::class.java) } returns bytes
            every { getHeaderString("Content-Type") } returns "not-a-mime-type"
            every { close() } returns Unit
        }
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:1", berichtId, bijlageId) } returns mockResp

        val (mimeType, _) = service.haalBijlage("BSN:1", berichtId, bijlageId)

        // Service zélf valideert niet; raw value doorgeven aan filter.
        assertEquals("not-a-mime-type", mimeType)
    }

    @Test
    fun `haalBijlage mapt magazijn-5xx naar 502`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val mockResp = mockk<Response> {
            every { status } returns 503
            every { close() } returns Unit
        }
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:1", berichtId, bijlageId) } returns mockResp

        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBijlage("BSN:1", berichtId, bijlageId)
        }
        assertEquals(502, ex.response.status)
    }

    @Test
    fun `haalBijlage propageert magazijn-404 als NotFound`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val mockResp = mockk<Response> {
            every { status } returns 404
            every { close() } returns Unit
        }
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:1", berichtId, bijlageId) } returns mockResp

        assertThrows(NotFoundException::class.java) {
            service.haalBijlage("BSN:1", berichtId, bijlageId)
        }
    }

    @Test
    fun `haalBijlage propageert magazijn-403 als Forbidden`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val mockResp = mockk<Response> {
            every { status } returns 403
            every { close() } returns Unit
        }
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:1", berichtId, bijlageId) } returns mockResp

        assertThrows(ForbiddenException::class.java) {
            service.haalBijlage("BSN:1", berichtId, bijlageId)
        }
    }

    @Test
    fun `haalBijlage propageert magazijn-401 met status 401`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val mockResp = mockk<Response> {
            every { status } returns 401
            every { close() } returns Unit
        }
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:1", berichtId, bijlageId) } returns mockResp

        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBijlage("BSN:1", berichtId, bijlageId)
        }

        assertEquals(401, ex.response.status)
    }

    @Test
    fun `haalBijlage behoudt magazijn-4xx-status die geen 401-403-404 is`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val mockResp = mockk<Response> {
            every { status } returns 422
            every { close() } returns Unit
        }
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:1", berichtId, bijlageId) } returns mockResp

        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBijlage("BSN:1", berichtId, bijlageId)
        }

        assertEquals(422, ex.response.status)
    }

    @Test
    fun `haalBijlage mapt magazijn-transportfout naar 502`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:1", berichtId, bijlageId) } throws ProcessingException("connect timeout")

        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBijlage("BSN:1", berichtId, bijlageId)
        }

        assertEquals(502, ex.response.status)
    }

    @Test
    fun `haalBijlage mapt ontbrekend Content-Type naar 502`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val mockResp = mockk<Response> {
            every { status } returns 200
            every { getHeaderString("Content-Type") } returns null
            every { close() } returns Unit
        }
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:1", berichtId, bijlageId) } returns mockResp

        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBijlage("BSN:1", berichtId, bijlageId)
        }
        assertEquals(502, ex.response.status)
    }
}
