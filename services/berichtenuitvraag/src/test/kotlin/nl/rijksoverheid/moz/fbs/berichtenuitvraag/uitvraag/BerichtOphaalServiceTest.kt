package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.core.MediaType
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
    private val service = BerichtOphaalService(sessiecache, magazijn)

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
            every { mediaType } returns MediaType.valueOf("application/pdf")
            every { close() } returns Unit
        }
        every { magazijn.bijlage("BSN:1", berichtId, bijlageId) } returns mockResp

        val (mimeType, content) = service.haalBijlage("BSN:1", berichtId, bijlageId)

        assertEquals("application/pdf", mimeType)
        assertArrayEquals(bytes, content)
    }

    @Test
    fun `haalBijlage gooit 500 als magazijn-respons status fout is`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val mockResp = mockk<Response> {
            every { status } returns 503
            every { close() } returns Unit
        }
        every { magazijn.bijlage("BSN:1", berichtId, bijlageId) } returns mockResp

        assertThrows(InternalServerErrorException::class.java) {
            service.haalBijlage("BSN:1", berichtId, bijlageId)
        }
    }

    @Test
    fun `haalBijlage gooit 500 als magazijn-respons geen Content-Type heeft`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val mockResp = mockk<Response> {
            every { status } returns 200
            every { mediaType } returns null
            every { close() } returns Unit
        }
        every { magazijn.bijlage("BSN:1", berichtId, bijlageId) } returns mockResp

        assertThrows(InternalServerErrorException::class.java) {
            service.haalBijlage("BSN:1", berichtId, bijlageId)
        }
    }
}
