package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class BerichtOphaalServiceTest {

    private val sessiecache: Sessiecache = mockk()
    private val ontvangerId = nl.rijksoverheid.moz.fbs.common.identificatie.Bsn("999990019")
    private val magazijn: MagazijnClient = mockk()
    private val router: MagazijnRouter = mockk {
        every { forMagazijn(any()) } returns magazijn
    }
    private val service = BerichtOphaalService(sessiecache, router)

    private fun domeinBericht(berichtId: UUID, magazijnId: String = "magazijn-a") = Bericht(
        berichtId = berichtId,
        afzender = "00000001003214345000",
        ontvanger = "999990019",
        onderwerp = "X",
        inhoud = "Inhoud",
        publicatietijdstip = java.time.Instant.parse("2026-05-26T10:00:00Z"),
        magazijnId = magazijnId,
        aantalBijlagen = 0,
    )

    private fun stubBerichtLookup(berichtId: UUID, magazijnId: String = "magazijn-a") {
        every { sessiecache.bericht(ontvangerId, berichtId) } returns domeinBericht(berichtId, magazijnId)
    }

    @Test
    fun `haalBericht mapt het domein-bericht naar het api-model`() {
        val id = UUID.randomUUID()
        every { sessiecache.bericht(ontvangerId, id) } returns domeinBericht(id)

        val result = service.haalBericht("BSN:999990019", id)

        assertEquals(id, result.berichtId)
        assertEquals("magazijn-a", result.magazijnId)
        assertEquals("/api/v1/berichten/$id", result.links.self.href)
    }

    @Test
    fun `haalBericht geeft 404 wanneer de cache het bericht niet kent`() {
        val id = UUID.randomUUID()
        every { sessiecache.bericht(ontvangerId, id) } returns null

        assertThrows(NotFoundException::class.java) {
            service.haalBericht("BSN:999990019", id)
        }
    }

    @Test
    fun `haalBijlage geeft 404 wanneer de cache het bericht niet kent`() {
        val id = UUID.randomUUID()
        every { sessiecache.bericht(ontvangerId, id) } returns null

        assertThrows(NotFoundException::class.java) {
            service.haalBijlage("BSN:999990019", id, UUID.randomUUID())
        }
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
        every { magazijn.bijlage("BSN:999990019", berichtId, bijlageId) } returns mockResp

        val (mimeType, content) = service.haalBijlage("BSN:999990019", berichtId, bijlageId)

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
        every { magazijn.bijlage("BSN:999990019", berichtId, bijlageId) } returns mockResp

        val (mimeType, _) = service.haalBijlage("BSN:999990019", berichtId, bijlageId)

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
        every { magazijn.bijlage("BSN:999990019", berichtId, bijlageId) } returns mockResp

        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBijlage("BSN:999990019", berichtId, bijlageId)
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
        every { magazijn.bijlage("BSN:999990019", berichtId, bijlageId) } returns mockResp

        assertThrows(NotFoundException::class.java) {
            service.haalBijlage("BSN:999990019", berichtId, bijlageId)
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
        every { magazijn.bijlage("BSN:999990019", berichtId, bijlageId) } returns mockResp

        assertThrows(ForbiddenException::class.java) {
            service.haalBijlage("BSN:999990019", berichtId, bijlageId)
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
        every { magazijn.bijlage("BSN:999990019", berichtId, bijlageId) } returns mockResp

        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBijlage("BSN:999990019", berichtId, bijlageId)
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
        every { magazijn.bijlage("BSN:999990019", berichtId, bijlageId) } returns mockResp

        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBijlage("BSN:999990019", berichtId, bijlageId)
        }

        assertEquals(422, ex.response.status)
    }

    @Test
    fun `haalBijlage mapt magazijn-transportfout naar 502`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:999990019", berichtId, bijlageId) } throws ProcessingException("connect timeout")

        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBijlage("BSN:999990019", berichtId, bijlageId)
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
        every { magazijn.bijlage("BSN:999990019", berichtId, bijlageId) } returns mockResp

        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBijlage("BSN:999990019", berichtId, bijlageId)
        }
        assertEquals(502, ex.response.status)
    }

    @Test
    fun `haalBijlage mapt body-read-fout (afgekapte stream) naar 502`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val mockResp = mockk<Response> {
            every { status } returns 200
            every { getHeaderString("Content-Type") } returns "application/pdf"
            every { readEntity(ByteArray::class.java) } throws ProcessingException("stream afgekapt")
            every { close() } returns Unit
        }
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:999990019", berichtId, bijlageId) } returns mockResp

        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBijlage("BSN:999990019", berichtId, bijlageId)
        }

        // Een fout tijdens het lezen van de body ná een geldige 200 is een upstream-
        // storing → 502, niet een 500 via de UncaughtExceptionMapper.
        assertEquals(502, ex.response.status)
        verify { mockResp.close() }
    }

    // De Quarkus REST-client kan bij een upstream-fout zélf een WebApplicationException
    // gooien i.p.v. een Response met fout-status terug te geven. Die catch-tak (incl. het
    // sluiten van de upstream-response tegen connectie-lek) wordt door bovenstaande
    // Response-gebaseerde tests niet geraakt; de twee tests hieronder dekken hem expliciet.
    @Test
    fun `haalBijlage mapt door REST-client geworpen WAE (5xx) naar 502 en sluit de upstream-response`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val upstreamResp = mockk<Response>(relaxed = true) {
            every { status } returns 503
        }
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:999990019", berichtId, bijlageId) } throws WebApplicationException("upstream kapot", upstreamResp)

        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBijlage("BSN:999990019", berichtId, bijlageId)
        }

        assertEquals(502, ex.response.status)
        verify { upstreamResp.close() }
    }

    @Test
    fun `haalBijlage mapt door REST-client geworpen WAE zonder response (transport-fout) naar 502`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val waeZonderResponse = mockk<WebApplicationException>(relaxed = true) {
            every { response } returns null
        }
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:999990019", berichtId, bijlageId) } throws waeZonderResponse

        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBijlage("BSN:999990019", berichtId, bijlageId)
        }

        assertEquals(502, ex.response.status)
    }

    // De twee close()-failure-takken zijn pure defensieve logging (connectie-pool-lek-
    // diagnose): een falende close mag de echte response/fout niet maskeren. Ze hebben
    // geen invloed op status of body, maar onderstaande tests pinnen dat gedrag zodat een
    // toekomstige refactor de runCatching-guard niet stilletjes weghaalt.
    @Test
    fun `haalBijlage retourneert normaal ook als response-close faalt (finally-tak)`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val bytes = byteArrayOf(9, 8, 7)
        val mockResp = mockk<Response> {
            every { status } returns 200
            every { readEntity(ByteArray::class.java) } returns bytes
            every { getHeaderString("Content-Type") } returns "application/pdf"
            every { close() } throws RuntimeException("pool-close kapot")
        }
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:999990019", berichtId, bijlageId) } returns mockResp

        val (mimeType, content) = service.haalBijlage("BSN:999990019", berichtId, bijlageId)

        // Falende close mag de geslaagde read niet kapotmaken: bytes komen normaal terug.
        assertEquals("application/pdf", mimeType)
        assertArrayEquals(bytes, content)
        verify { mockResp.close() }
    }

    @Test
    fun `haalBijlage mapt WAE (5xx) naar 502 ook als het sluiten van de upstream-response faalt`() {
        val berichtId = UUID.randomUUID()
        val bijlageId = UUID.randomUUID()
        val upstreamResp = mockk<Response>(relaxed = true) {
            every { status } returns 503
            every { close() } throws RuntimeException("close kapot")
        }
        stubBerichtLookup(berichtId)
        every { magazijn.bijlage("BSN:999990019", berichtId, bijlageId) } throws WebApplicationException("upstream kapot", upstreamResp)

        val ex = assertThrows(WebApplicationException::class.java) {
            service.haalBijlage("BSN:999990019", berichtId, bijlageId)
        }

        // Een falende close mag de echte 502-mapping niet overschaduwen.
        assertEquals(502, ex.response.status)
        verify { upstreamResp.close() }
    }
}
