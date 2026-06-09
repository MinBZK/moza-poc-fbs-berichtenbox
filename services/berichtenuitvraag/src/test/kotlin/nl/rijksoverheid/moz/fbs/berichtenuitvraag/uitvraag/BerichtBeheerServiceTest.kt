package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtStatus
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BerichtBeheerServiceTest {

    private val sessiecache: Sessiecache = mockk(relaxed = true)
    private val magazijn: MagazijnClient = mockk(relaxed = true)
    private val router: MagazijnRouter = mockk {
        every { forMagazijn(any()) } returns magazijn
    }
    private val service = BerichtBeheerService(sessiecache, router)

    private val id: UUID = UUID.randomUUID()
    private val ontvanger = "BSN:999990019"
    private val ontvangerId = Bsn("999990019")
    private val magazijnId = "magazijn-a"
    private val patch = BerichtPatch().apply { status = BerichtStatus.GELEZEN }
    private val bijgewerkt = Bericht(
        berichtId = id,
        afzender = "00000001003214345000",
        ontvanger = Bsn("999990019"),
        onderwerp = "X",
        inhoud = "Inhoud",
        publicatietijdstip = Instant.parse("2026-05-26T10:00:00Z"),
        magazijnId = "magazijn-a",
        aantalBijlagen = 0,
        status = Leesstatus.GELEZEN,
    )

    @Test
    fun `patch happy-path magazijn-eerst dan cache geeft bericht uit cache`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.werkBerichtBij(ontvangerId, any(), any(), any()) } returns bijgewerkt

        val result = service.patch(ontvanger, id, magazijnId, patch)

        assertEquals(id, result.berichtId)
        verifyOrder {
            // Assert de gemapte patch (niet `any()`): borgt dat GELEZEN → gelezen=true
            // wordt vertaald; een geïnverteerde of weggevallen mapping faalt hier.
            magazijn.patchBericht(ontvanger, id, UitvraagDtoMapper.MagazijnPatch(gelezen = true, map = null))
            sessiecache.werkBerichtBij(ontvangerId, id, Leesstatus.GELEZEN, null)
        }
    }

    @Test
    fun `patch ONGELEZEN mapt naar gelezen-false richting magazijn`() {
        val ongelezenPatch = BerichtPatch().apply { status = BerichtStatus.ONGELEZEN }
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.werkBerichtBij(ontvangerId, any(), any(), any()) } returns bijgewerkt

        service.patch(ontvanger, id, magazijnId, ongelezenPatch)

        verify { magazijn.patchBericht(ontvanger, id, UitvraagDtoMapper.MagazijnPatch(gelezen = false, map = null)) }
    }

    @Test
    fun `patch met lege patch geeft 400 zonder magazijn-write`() {
        // Spec: minProperties 1; afwijzen vóór de write zodat geen no-op het
        // magazijn raakt.
        val ex = assertThrows(WebApplicationException::class.java) {
            service.patch(ontvanger, id, magazijnId, BerichtPatch())
        }

        assertEquals(400, ex.response.status)
        verify(exactly = 0) { magazijn.patchBericht(any(), any(), any()) }
    }

    @Test
    fun `patch magazijn-5xx normaliseert naar 502 en raakt cache niet aan`() {
        every { magazijn.patchBericht(any(), any(), any()) } throws InternalServerErrorException("magazijn-down")

        val ex = assertThrows(WebApplicationException::class.java) {
            service.patch(ontvanger, id, magazijnId, patch)
        }

        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
        verify(exactly = 0) { sessiecache.werkBerichtBij(ontvangerId, any(), any(), any()) }
    }

    @Test
    fun `patch cache-faal triggert invalidate en gooit 502`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.werkBerichtBij(ontvangerId, any(), any(), any()) } throws WebApplicationException("cache-down", 503)
        every { sessiecache.verwijder(ontvangerId, any()) } returns Unit

        val ex = assertThrows(WebApplicationException::class.java) {
            service.patch(ontvanger, id, magazijnId, patch)
        }

        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
        verify { sessiecache.verwijder(ontvangerId, id) }
    }

    @Test
    fun `patch cache-faal en invalidate-faal gooit nog steeds 502`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.werkBerichtBij(ontvangerId, any(), any(), any()) } throws WebApplicationException("cache-down", 503)
        every { sessiecache.verwijder(ontvangerId, any()) } throws WebApplicationException("ook-down", 503)

        val ex = assertThrows(WebApplicationException::class.java) {
            service.patch(ontvanger, id, magazijnId, patch)
        }

        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
    }

    @Test
    fun `patch cache-miss geeft 404 zonder compensatie`() {
        // null = bericht niet (meer) in cache; magazijn-write was al door — 404 mét
        // desync-alert in de log, geen invalidate (er valt niets te invalideren).
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.werkBerichtBij(ontvangerId, any(), any(), any()) } returns null

        assertThrows(NotFoundException::class.java) {
            service.patch(ontvanger, id, magazijnId, patch)
        }

        verify(exactly = 0) { sessiecache.verwijder(ontvangerId, any()) }
    }

    @Test
    fun `patch cache-4xx propageert onveranderd zonder compensatie`() {
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.werkBerichtBij(ontvangerId, any(), any(), any()) } throws
            WebApplicationException("cache-conflict", Response.Status.CONFLICT)

        val ex = assertThrows(WebApplicationException::class.java) {
            service.patch(ontvanger, id, magazijnId, patch)
        }

        assertEquals(Response.Status.CONFLICT.statusCode, ex.response.status)
        verify(exactly = 0) { sessiecache.verwijder(ontvangerId, any()) }
    }

    @Test
    fun `verwijder happy-path doet beide deletes in volgorde`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijder(ontvangerId, any()) } returns Unit

        service.verwijder(ontvanger, id, magazijnId)

        verifyOrder {
            magazijn.verwijderBericht(ontvanger, id)
            sessiecache.verwijder(ontvangerId, id)
        }
    }

    @Test
    fun `verwijder magazijn-5xx normaliseert naar 502 en raakt cache niet aan`() {
        every { magazijn.verwijderBericht(any(), any()) } throws InternalServerErrorException("magazijn-down")

        val ex = assertThrows(WebApplicationException::class.java) {
            service.verwijder(ontvanger, id, magazijnId)
        }

        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
        verify(exactly = 0) { sessiecache.verwijder(ontvangerId, any()) }
    }

    @Test
    fun `verwijder cache-faal gooit 502 met compensatie-invalidate`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        // Eerste call faalt, tweede (compensatie) slaagt.
        var aanroepen = 0
        every { sessiecache.verwijder(ontvangerId, any()) } answers {
            aanroepen++

            if (aanroepen == 1) throw WebApplicationException("cache-down", 503)
        }

        val ex = assertThrows(WebApplicationException::class.java) {
            service.verwijder(ontvanger, id, magazijnId)
        }

        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
        verify(exactly = 2) { sessiecache.verwijder(ontvangerId, id) }
    }

    @Test
    fun `verwijder cache-403 propageert zonder compensatie`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijder(ontvangerId, any()) } throws ForbiddenException("cache-weigert")

        val ex = assertThrows(ForbiddenException::class.java) {
            service.verwijder(ontvanger, id, magazijnId)
        }

        assertEquals(Response.Status.FORBIDDEN.statusCode, ex.response.status)
        // Eén call (de write die 403 gaf); geen tweede compensatie-call.
        verify(exactly = 1) { sessiecache.verwijder(ontvangerId, id) }
    }

    @Test
    fun `verwijder cache-faal + compensatie-faal gooit 502`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijder(ontvangerId, any()) } throws WebApplicationException("cache-down", 503)

        val ex = assertThrows(WebApplicationException::class.java) {
            service.verwijder(ontvanger, id, magazijnId)
        }

        assertEquals(Response.Status.BAD_GATEWAY.statusCode, ex.response.status)
        verify(exactly = 2) { sessiecache.verwijder(ontvangerId, id) }
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
        verify(exactly = 0) { sessiecache.werkBerichtBij(ontvangerId, any(), any(), any()) }
    }

    @Test
    fun `verwijder magazijn-401 propageert 1-op-1 zonder 502 en zonder cache-call`() {
        every { magazijn.verwijderBericht(any(), any()) } throws
            WebApplicationException("magazijn-401", Response.Status.UNAUTHORIZED)

        val ex = assertThrows(WebApplicationException::class.java) {
            service.verwijder(ontvanger, id, magazijnId)
        }

        assertEquals(Response.Status.UNAUTHORIZED.statusCode, ex.response.status)
        verify(exactly = 0) { sessiecache.verwijder(ontvangerId, any()) }
    }

    @Test
    fun `patch routeert via meegegeven magazijnId zonder cache-lookup`() {
        // De router krijgt exact de magazijnId-parameter; geen cache-lookup vooraf,
        // dus een verlopen/lege cache veroorzaakt geen 404 of 502 op de magazijn-write.
        every { magazijn.patchBericht(any(), any(), any()) } returns Unit
        every { sessiecache.werkBerichtBij(ontvangerId, any(), any(), any()) } returns bijgewerkt

        service.patch(ontvanger, id, "magazijn-X", patch)

        verify { router.forMagazijn("magazijn-X") }
        verify(exactly = 0) { sessiecache.bericht(ontvangerId, any()) }
    }

    @Test
    fun `verwijder routeert via meegegeven magazijnId zonder cache-lookup`() {
        every { magazijn.verwijderBericht(any(), any()) } returns Unit
        every { sessiecache.verwijder(ontvangerId, any()) } returns Unit

        service.verwijder(ontvanger, id, "magazijn-X")

        verify { router.forMagazijn("magazijn-X") }
        verify(exactly = 0) { sessiecache.bericht(ontvangerId, any()) }
    }
}
