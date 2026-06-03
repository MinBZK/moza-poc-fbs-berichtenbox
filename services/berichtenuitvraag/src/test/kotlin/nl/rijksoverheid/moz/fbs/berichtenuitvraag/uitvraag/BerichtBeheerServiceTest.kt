package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.ws.rs.ForbiddenException
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
    private val ontvanger = "BSN:999990019"
    private val magazijnId = "magazijn-a"
    private val patch = BerichtPatch().apply { status = BerichtStatus.GELEZEN }
    private val updated = Bericht().apply {
        berichtId = id
        this.magazijnId = "magazijn-a"
    }

    @Test
    fun `patch happy-path magazijn-eerst dan cache geeft bericht uit cache`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.patchBericht(any(), any(), any()) } returns updated

        val result = service.patch(ontvanger, id, magazijnId, patch)

        assertEquals(id, result.berichtId)
        verifyOrder {
            // Assert de gemapte patch (niet `any()`): borgt dat GELEZEN → gelezen=true
            // wordt vertaald; een geïnverteerde of weggevallen mapping faalt hier.
            magazijn.patchBericht(ontvanger, id, UitvraagDtoMapper.MagazijnPatch(gelezen = true, map = null))
            sessiecache.patchBericht(ontvanger, id, patch)
        }
    }

    @Test
    fun `patch ONGELEZEN mapt naar gelezen-false richting magazijn`() {
        val ongelezenPatch = BerichtPatch().apply { status = BerichtStatus.ONGELEZEN }
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.patchBericht(any(), any(), any()) } returns updated

        service.patch(ontvanger, id, magazijnId, ongelezenPatch)

        verify { magazijn.patchBericht(ontvanger, id, UitvraagDtoMapper.MagazijnPatch(gelezen = false, map = null)) }
    }

    @Test
    fun `patch magazijn-5xx normaliseert naar 502 en raakt cache niet aan`() {
        every { magazijn.patchBericht(any(), any(), any()) } throws InternalServerErrorException("magazijn-down")

        val ex = assertThrows(WebApplicationException::class.java) {
            service.patch(ontvanger, id, magazijnId, patch)
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
            service.patch(ontvanger, id, magazijnId, patch)
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
            service.patch(ontvanger, id, magazijnId, patch)
        }
        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
    }

    @Test
    fun `verwijder happy-path doet beide deletes in volgorde`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijderBericht(any(), any()) } returns Unit

        service.verwijder(ontvanger, id, magazijnId)

        verifyOrder {
            magazijn.verwijderBericht(ontvanger, id)
            sessiecache.verwijderBericht(ontvanger, id)
        }
    }

    @Test
    fun `verwijder magazijn-5xx normaliseert naar 502 en raakt cache niet aan`() {
        every { magazijn.verwijderBericht(any(), any()) } throws InternalServerErrorException("magazijn-down")

        val ex = assertThrows(WebApplicationException::class.java) {
            service.verwijder(ontvanger, id, magazijnId)
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
            service.verwijder(ontvanger, id, magazijnId)
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
            service.patch(ontvanger, id, magazijnId, patch)
        }
        verify(exactly = 0) { sessiecache.verwijderBericht(any(), any()) }
    }

    @Test
    fun `patch cache-403 herverpakt naar ForbiddenException zonder compensatie`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.patchBericht(any(), any(), any()) } throws ForbiddenException("cache-weigert")

        // 403 = contract-bug (geen transport-storing): status-behoudend herverpakt
        // (herverpakCache4xx) zonder de cache-internals/PII-body te lekken, en zónder
        // compensatie-invalidate.
        val ex = assertThrows(ForbiddenException::class.java) {
            service.patch(ontvanger, id, magazijnId, patch)
        }

        assertEquals(Response.Status.FORBIDDEN.statusCode, ex.response.status)
        verify(exactly = 0) { sessiecache.verwijderBericht(any(), any()) }
    }

    @Test
    fun `patch cache-generiek-4xx herverpakt status-behoudend zonder compensatie`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.patchBericht(any(), any(), any()) } throws
            WebApplicationException("cache-conflict", Response.Status.CONFLICT)

        // Een 4xx die geen 404/403 is (hier 409) valt op de else-tak van
        // herverpakCache4xx: een generieke WebApplicationException met dezelfde status.
        val ex = assertThrows(WebApplicationException::class.java) {
            service.patch(ontvanger, id, magazijnId, patch)
        }

        assertEquals(Response.Status.CONFLICT.statusCode, ex.response.status)
        verify(exactly = 0) { sessiecache.verwijderBericht(any(), any()) }
    }

    @Test
    fun `verwijder cache-403 herverpakt naar ForbiddenException zonder compensatie`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijderBericht(any(), any()) } throws ForbiddenException("cache-weigert")

        val ex = assertThrows(ForbiddenException::class.java) {
            service.verwijder(ontvanger, id, magazijnId)
        }

        assertEquals(Response.Status.FORBIDDEN.statusCode, ex.response.status)
        // Eén call (de write die 403 gaf); geen tweede compensatie-call.
        verify(exactly = 1) { sessiecache.verwijderBericht(ontvanger, id) }
    }

    @Test
    fun `verwijder cache-generiek-4xx herverpakt status-behoudend zonder compensatie`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijderBericht(any(), any()) } throws
            WebApplicationException("cache-conflict", Response.Status.CONFLICT)

        val ex = assertThrows(WebApplicationException::class.java) {
            service.verwijder(ontvanger, id, magazijnId)
        }

        assertEquals(Response.Status.CONFLICT.statusCode, ex.response.status)
        verify(exactly = 1) { sessiecache.verwijderBericht(ontvanger, id) }
    }

    @Test
    fun `patch cache-transport-fout (timeout) gooit 502 met compensatie`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.patchBericht(any(), any(), any()) } throws ProcessingException("connect timeout")
        every { sessiecache.verwijderBericht(any(), any()) } returns Unit

        val ex = assertThrows(WebApplicationException::class.java) {
            service.patch(ontvanger, id, magazijnId, patch)
        }
        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
        verify { sessiecache.verwijderBericht(ontvanger, id) }
    }

    @Test
    fun `verwijder cache-4xx propageert onveranderd zonder compensatie`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijderBericht(any(), any()) } throws NotFoundException("cache-mist-bericht")

        assertThrows(NotFoundException::class.java) {
            service.verwijder(ontvanger, id, magazijnId)
        }
        verify(exactly = 1) { sessiecache.verwijderBericht(ontvanger, id) }
    }

    @Test
    fun `verwijder cache-transport-fout (timeout) gooit 502 met compensatie`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        // Eerste call gooit ProcessingException, compensatie-call slaagt.
        every { sessiecache.verwijderBericht(any(), any()) } throws ProcessingException("connect timeout") andThen Unit

        val ex = assertThrows(WebApplicationException::class.java) {
            service.verwijder(ontvanger, id, magazijnId)
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
            service.verwijder(ontvanger, id, magazijnId)
        }
        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
    }

    @Test
    fun `patch doet geen sessiecache-lookup vóór de magazijn-write`() {
        // De client geeft de magazijnId mee (uit een eerdere GET); de service hoeft de
        // cache niet meer te raadplegen om het bron-magazijn te bepalen. Dat betekent
        // ook dat een verlopen/lege cache geen 404 of 502 meer op PATCH veroorzaakt.
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.patchBericht(any(), any(), any()) } returns updated

        service.patch(ontvanger, id, magazijnId, patch)

        verify(exactly = 0) { sessiecache.bericht(any(), any()) }
    }

    @Test
    fun `verwijder doet geen sessiecache-lookup vóór de magazijn-write`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijderBericht(any(), any()) } returns Unit

        service.verwijder(ontvanger, id, magazijnId)

        verify(exactly = 0) { sessiecache.bericht(any(), any()) }
    }

    @Test
    fun `patch magazijn-403 propageert 1-op-1 zonder 502 en zonder cache-call`() {
        // Autorisatie afgewezen door het magazijn (4xx) is geen transport-storing:
        // de status moet 1-op-1 naar de client, niet hergetypeerd naar 502, en de
        // cache mag niet aangeraakt worden (magazijn-write is niet doorgegaan).
        every { magazijn.patchBericht(any(), any(), any()) } throws ForbiddenException("magazijn-403")

        val ex = assertThrows(WebApplicationException::class.java) {
            service.patch(ontvanger, id, magazijnId, patch)
        }

        assertEquals(Response.Status.FORBIDDEN.statusCode, ex.response.status)
        verify(exactly = 0) { sessiecache.patchBericht(any(), any(), any()) }
    }

    @Test
    fun `verwijder magazijn-401 propageert 1-op-1 zonder 502 en zonder cache-call`() {
        every { magazijn.verwijderBericht(any(), any()) } throws
            WebApplicationException("magazijn-401", Response.Status.UNAUTHORIZED)

        val ex = assertThrows(WebApplicationException::class.java) {
            service.verwijder(ontvanger, id, magazijnId)
        }

        assertEquals(Response.Status.UNAUTHORIZED.statusCode, ex.response.status)
        verify(exactly = 0) { sessiecache.verwijderBericht(any(), any()) }
    }

    @Test
    fun `patch routeert via meegegeven magazijnId`() {
        // De router krijgt exact de magazijnId-parameter; geen cache-lookup tussendoor.
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.patchBericht(any(), any(), any()) } returns updated

        service.patch(ontvanger, id, "magazijn-X", patch)

        verify { router.forMagazijn("magazijn-X") }
    }

    @Test
    fun `verwijder routeert via meegegeven magazijnId`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijderBericht(any(), any()) } returns Unit

        service.verwijder(ontvanger, id, "magazijn-X")

        verify { router.forMagazijn("magazijn-X") }
    }
}
