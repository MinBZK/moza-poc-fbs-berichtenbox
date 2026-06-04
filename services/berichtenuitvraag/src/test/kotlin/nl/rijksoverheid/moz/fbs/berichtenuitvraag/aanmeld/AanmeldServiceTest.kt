package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AanmeldServiceTest {

    private val sessiecache = mockk<Sessiecache>()
    private val dedup = mockk<AanmeldDeduplicatie>()
    private val index = mockk<AfzenderMagazijnIndex>()
    private val logboek = mockk<LogboekContext>(relaxed = true)
    private val service = AanmeldService(sessiecache, dedup, index, logboek)

    private val afzender = "00000001003214345000"
    private val ontvangerBsn = "999990019"
    private val ontvanger = Identificatienummer.of(IdentificatienummerType.BSN, ontvangerBsn)
    private val berichtId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        every { dedup.eerstgezien(any()) } returns true
        every { dedup.verwijder(any()) } just Runs
        every { index.magazijnVoor(afzender) } returns "magazijn-a"
    }

    private fun event(
        id: String = "evt-1",
        source: String = "urn:nld:oin:$afzender:systeem:fbs-magazijn",
        specversion: String = "1.0",
        type: String = "nl.rijksoverheid.fbs.bericht.gepubliceerd",
        data: AangemeldBerichtData? = data(),
    ) = AangemeldCloudEvent(id, source, specversion, type, berichtId.toString(), Instant.now(), "application/json", null, data)

    private fun data(
        afzenderOin: String = afzender,
        ontvanger: AangemeldOntvanger? = AangemeldOntvanger("BSN", ontvangerBsn),
        onderwerp: String? = "Onderwerp",
        inhoud: String? = "Inhoud",
    ) = AangemeldBerichtData(berichtId, afzenderOin, ontvanger, onderwerp, inhoud, Instant.now(), Instant.now())

    @Test
    fun `happy path schrijft bericht met afgeleid magazijnId en geen bijlagen`() {
        val slot = slot<Bericht>()
        every { sessiecache.schrijfBericht(ontvanger, capture(slot)) } answers { slot.captured }

        service.verwerk(event())

        verify(exactly = 1) { sessiecache.schrijfBericht(ontvanger, any()) }
        assertEquals("magazijn-a", slot.captured.magazijnId)
        assertEquals(0, slot.captured.aantalBijlagen)
        assertEquals(afzender, slot.captured.afzender)
        assertEquals(ontvangerBsn, slot.captured.ontvanger)
        assertEquals(berichtId, slot.captured.berichtId)
        verify { logboek.dataSubjectId = ontvangerBsn }
    }

    @Test
    fun `duplicaat wordt overgeslagen zonder schrijven`() {
        every { dedup.eerstgezien("evt-1") } returns false

        service.verwerk(event())

        verify(exactly = 0) { sessiecache.schrijfBericht(ontvanger, any()) }
    }

    @Test
    fun `geen actieve sessie (404) wordt geaccepteerd-maar-overgeslagen`() {
        every { sessiecache.schrijfBericht(ontvanger, any()) } throws
            WebApplicationException("geen sessie", Response.Status.NOT_FOUND)

        // Geen exception: webhook geeft 202.
        service.verwerk(event())

        verify(exactly = 0) { dedup.verwijder(any()) }
    }

    @Test
    fun `cache onbereikbaar (503) propageert en rolt de marker terug`() {
        every { sessiecache.schrijfBericht(ontvanger, any()) } throws
            WebApplicationException("cache down", 503)

        val ex = assertThrows<WebApplicationException> { service.verwerk(event()) }

        assertEquals(503, ex.response.status)
        verify(exactly = 1) { dedup.verwijder("evt-1") }
    }

    @Test
    fun `onbekende bron-OIN geeft 400`() {
        every { index.magazijnVoor(afzender) } returns null

        val ex = assertThrows<WebApplicationException> { service.verwerk(event()) }

        assertEquals(400, ex.response.status)
        verify(exactly = 0) { sessiecache.schrijfBericht(ontvanger, any()) }
    }

    @Test
    fun `ongeldig afzender-OIN geeft 400`() {
        val ex = assertThrows<WebApplicationException> {
            service.verwerk(event(data = data(afzenderOin = "geen-oin")))
        }

        assertEquals(400, ex.response.status)
    }

    @Test
    fun `onbekend ontvanger-type geeft 400`() {
        val ex = assertThrows<WebApplicationException> {
            service.verwerk(event(data = data(ontvanger = AangemeldOntvanger("XXX", ontvangerBsn))))
        }

        assertEquals(400, ex.response.status)
    }

    @Test
    fun `ongeldige ontvanger-waarde (elfproef) geeft 400`() {
        val ex = assertThrows<WebApplicationException> {
            service.verwerk(event(data = data(ontvanger = AangemeldOntvanger("BSN", "123456789"))))
        }

        assertEquals(400, ex.response.status)
    }

    @Test
    fun `verkeerde specversion geeft 400 zonder dedup`() {
        val ex = assertThrows<WebApplicationException> { service.verwerk(event(specversion = "0.3")) }

        assertEquals(400, ex.response.status)
        verify(exactly = 0) { dedup.eerstgezien(any()) }
    }

    @Test
    fun `source buiten urn nld geeft 400`() {
        val ex = assertThrows<WebApplicationException> {
            service.verwerk(event(source = "https://evil.example/source"))
        }

        assertEquals(400, ex.response.status)
    }

    @Test
    fun `onverwacht event-type geeft 400`() {
        val ex = assertThrows<WebApplicationException> {
            service.verwerk(event(type = "nl.rijksoverheid.fbs.bericht.verwijderd"))
        }

        assertEquals(400, ex.response.status)
    }

    @Test
    fun `ontbrekende data geeft 400`() {
        val ex = assertThrows<WebApplicationException> { service.verwerk(event(data = null)) }

        assertEquals(400, ex.response.status)
    }

    @Test
    fun `ontbrekend verplicht data-veld geeft 400`() {
        val ex = assertThrows<WebApplicationException> {
            service.verwerk(event(data = data(onderwerp = null)))
        }

        assertEquals(400, ex.response.status)
    }

    @Test
    fun `lege id geeft 400`() {
        val ex = assertThrows<WebApplicationException> { service.verwerk(event(id = "")) }

        assertEquals(400, ex.response.status)
    }
}
