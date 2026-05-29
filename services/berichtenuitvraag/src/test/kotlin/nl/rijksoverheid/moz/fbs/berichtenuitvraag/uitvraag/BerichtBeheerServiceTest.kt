package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class BerichtBeheerServiceTest {

    private val sessiecache: SessiecacheClient = mockk(relaxed = true)
    private val magazijn: MagazijnClient = mockk(relaxed = true)
    private val router: MagazijnRouter = mockk {
        every { forMagazijn(any()) } returns magazijn
    }
    private val service = BerichtBeheerService(sessiecache, router)

    private val id: UUID = UUID.randomUUID()
    private val ontvanger = "BSN:123456782"
    private val patch = BerichtPatch().apply { status = BerichtStatus.GELEZEN }
    private val updated = Bericht().apply {
        berichtId = id
        magazijnId = "magazijn-a"
    }

    init {
        // Default-stub: lookup levert een bericht met `magazijnId=magazijn-a`;
        // tests die een ander pad nodig hebben (cache-miss → 404) overrulen deze.
        every { sessiecache.bericht(any(), id) } returns updated
    }

    @Test
    fun `patch happy-path magazijn-eerst dan cache geeft bericht uit cache`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.patchBericht(any(), any(), any()) } returns updated

        val result = service.patch(ontvanger, id, patch)

        assertEquals(id, result.berichtId)
        verifyOrder {
            magazijn.patchBericht(ontvanger, id, any())
            sessiecache.patchBericht(ontvanger, id, patch)
        }
    }

    @Test
    fun `patch magazijn-5xx normaliseert naar 502 en raakt cache niet aan`() {
        every { magazijn.patchBericht(any(), any(), any()) } throws InternalServerErrorException("magazijn-down")

        val ex = assertThrows(WebApplicationException::class.java) {
            service.patch(ontvanger, id, patch)
        }
        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
        verify(exactly = 0) { sessiecache.patchBericht(any(), any(), any()) }
    }

    @Test
    fun `patch cache-faal triggert invalidate en gooit 502`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.patchBericht(any(), any(), any()) } throws InternalServerErrorException("cache-down")
        every { sessiecache.verwijderBericht(any(), any()) } returns Unit

        val ex = assertThrows(WebApplicationException::class.java) {
            service.patch(ontvanger, id, patch)
        }
        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
        verify { sessiecache.verwijderBericht(ontvanger, id) }
    }

    @Test
    fun `patch cache-faal en invalidate-faal gooit nog steeds 502`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.patchBericht(any(), any(), any()) } throws InternalServerErrorException("cache-down")
        every { sessiecache.verwijderBericht(any(), any()) } throws InternalServerErrorException("ook-down")

        val ex = assertThrows(WebApplicationException::class.java) {
            service.patch(ontvanger, id, patch)
        }
        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
    }

    @Test
    fun `verwijder happy-path doet beide deletes in volgorde`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijderBericht(any(), any()) } returns Unit

        service.verwijder(ontvanger, id)

        verifyOrder {
            magazijn.verwijderBericht(ontvanger, id)
            sessiecache.verwijderBericht(ontvanger, id)
        }
    }

    @Test
    fun `verwijder magazijn-5xx normaliseert naar 502 en raakt cache niet aan`() {
        every { magazijn.verwijderBericht(any(), any()) } throws InternalServerErrorException("magazijn-down")

        val ex = assertThrows(WebApplicationException::class.java) {
            service.verwijder(ontvanger, id)
        }
        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
        verify(exactly = 0) { sessiecache.verwijderBericht(any(), any()) }
    }

    @Test
    fun `verwijder cache-faal gooit 502 met compensatie-invalidate`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        // Eerste call faalt, tweede (compensatie) slaagt.
        every { sessiecache.verwijderBericht(any(), any()) } throws InternalServerErrorException("cache-down") andThen Unit

        val ex = assertThrows(WebApplicationException::class.java) {
            service.verwijder(ontvanger, id)
        }
        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
        verify(exactly = 2) { sessiecache.verwijderBericht(ontvanger, id) }
    }

    @Test
    fun `patch cache-4xx propageert onveranderd zonder compensatie`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.patchBericht(any(), any(), any()) } throws NotFoundException("cache-mist-bericht")

        // 4xx betekent contract-bug (bericht niet in cache), geen transport-fout.
        // De fout moet 1-op-1 propageren; we triggeren GEEN invalidate (cache is
        // al niet de bron van waarheid en kan stale state houden tot TTL).
        assertThrows(NotFoundException::class.java) {
            service.patch(ontvanger, id, patch)
        }
        verify(exactly = 0) { sessiecache.verwijderBericht(any(), any()) }
    }

    @Test
    fun `patch cache-transport-fout (timeout) gooit 502 met compensatie`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.patchBericht(any(), any(), any()) } throws ProcessingException("connect timeout")
        every { sessiecache.verwijderBericht(any(), any()) } returns Unit

        val ex = assertThrows(WebApplicationException::class.java) {
            service.patch(ontvanger, id, patch)
        }
        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
        verify { sessiecache.verwijderBericht(ontvanger, id) }
    }

    @Test
    fun `verwijder cache-4xx propageert onveranderd zonder compensatie`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijderBericht(any(), any()) } throws NotFoundException("cache-mist-bericht")

        assertThrows(NotFoundException::class.java) {
            service.verwijder(ontvanger, id)
        }
        verify(exactly = 1) { sessiecache.verwijderBericht(ontvanger, id) }
    }

    @Test
    fun `verwijder cache-transport-fout (timeout) gooit 502 met compensatie`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        // Eerste call gooit ProcessingException, compensatie-call slaagt.
        every { sessiecache.verwijderBericht(any(), any()) } throws ProcessingException("connect timeout") andThen Unit

        val ex = assertThrows(WebApplicationException::class.java) {
            service.verwijder(ontvanger, id)
        }
        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
        verify(exactly = 2) { sessiecache.verwijderBericht(ontvanger, id) }
    }

    @Test
    fun `verwijder cache-faal + compensatie-ProcessingException gooit 502`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijderBericht(any(), any()) } throwsMany listOf(
            InternalServerErrorException("cache-down"),
            ProcessingException("compensatie-timeout"),
        )

        val ex = assertThrows(WebApplicationException::class.java) {
            service.verwijder(ontvanger, id)
        }
        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
    }

    @Test
    fun `patch met cache-miss op lookup propageert 404 zonder magazijn-call`() {
        // Sliding-TTL kan tussen UI-flow en write expireren; client moet eerst
        // re-fetchen (en daarbij weer de routerings-info naar de cache schrijven).
        every { sessiecache.bericht(any(), id) } throws NotFoundException("cache-miss")

        assertThrows(NotFoundException::class.java) {
            service.patch(ontvanger, id, patch)
        }
        verify(exactly = 0) { magazijn.patchBericht(any(), any(), any()) }
    }

    @Test
    fun `verwijder met cache-miss op lookup propageert 404 zonder magazijn-call`() {
        every { sessiecache.bericht(any(), id) } throws NotFoundException("cache-miss")

        assertThrows(NotFoundException::class.java) {
            service.verwijder(ontvanger, id)
        }
        verify(exactly = 0) { magazijn.verwijderBericht(any(), any()) }
    }

    @Test
    fun `patch met cache-transportfout op lookup gooit 502 zonder magazijn-call`() {
        every { sessiecache.bericht(any(), id) } throws ProcessingException("connect timeout")

        val ex = assertThrows(WebApplicationException::class.java) {
            service.patch(ontvanger, id, patch)
        }

        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
        verify(exactly = 0) { magazijn.patchBericht(any(), any(), any()) }
    }

    @Test
    fun `verwijder met cache-5xx op lookup gooit 502 zonder magazijn-call`() {
        every { sessiecache.bericht(any(), id) } throws InternalServerErrorException("cache-down")

        val ex = assertThrows(WebApplicationException::class.java) {
            service.verwijder(ontvanger, id)
        }

        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
        verify(exactly = 0) { magazijn.verwijderBericht(any(), any()) }
    }

    @Test
    fun `patch routeert via magazijnId uit cache, niet via client-input`() {
        // Defense-in-depth: ook al zou ergens later een client-meegegeven id
        // door de validatie heen sluipen, de routering pakt uitsluitend de
        // waarde uit de cache.
        every { sessiecache.bericht(any(), id) } returns Bericht().apply {
            berichtId = id
            magazijnId = "magazijn-X"
        }
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.patchBericht(any(), any(), any()) } returns updated

        service.patch(ontvanger, id, patch)

        verify { router.forMagazijn("magazijn-X") }
    }
}
