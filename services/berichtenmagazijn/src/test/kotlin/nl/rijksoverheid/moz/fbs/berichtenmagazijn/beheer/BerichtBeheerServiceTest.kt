package nl.rijksoverheid.moz.fbs.berichtenmagazijn.beheer

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatus
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusPatch
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Identificatienummer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class BerichtBeheerServiceTest {

    private val berichtRepository = mockk<BerichtRepository>()
    private val statusRepository = mockk<BerichtStatusRepository>()
    private val bijlageRepository = mockk<BijlageRepository>(relaxed = true)
    private val service = BerichtBeheerService(berichtRepository, statusRepository, bijlageRepository)

    private val ontvanger: Identificatienummer = Bsn("999993653")
    private val anderePersoon: Identificatienummer = Bsn("123456782")

    private fun bericht(ontvangerOp: Identificatienummer = ontvanger): Bericht = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = Oin("00000001003214345000"),
        ontvanger = ontvangerOp,
        onderwerp = "Voorlopige aanslag",
        inhoud = "Inhoud",
        tijdstipOntvangst = Instant.parse("2026-05-13T10:00:00Z"),
    )

    @Test
    fun `wijzigStatus geeft patch door aan repository en retourneert verrijkt bericht`() {
        val b = bericht()
        val patch = BerichtStatusPatch(gelezen = true, map = "archief")
        val nieuweStatus = BerichtStatus(gelezen = true, map = "archief", gewijzigdOp = Instant.now())
        val patchSlot = slot<BerichtStatusPatch>()
        every { berichtRepository.findByBerichtId(b.berichtId) } returns b
        every {
            statusRepository.upsert(b.berichtId, capture(patchSlot), any())
        } returns nieuweStatus
        every { bijlageRepository.metadataVoorBericht(b.berichtId) } returns emptyList()

        val resultaat = service.wijzigStatus(b.berichtId, ontvanger, patch)

        assertEquals(patch, patchSlot.captured)
        assertEquals(nieuweStatus, resultaat.status)
    }

    @Test
    fun `wijzigStatus gooit Forbidden bij ontvanger-mismatch`() {
        val b = bericht(ontvangerOp = anderePersoon)
        every { berichtRepository.findByBerichtId(b.berichtId) } returns b

        assertThrows<ForbiddenException> {
            service.wijzigStatus(b.berichtId, ontvanger, BerichtStatusPatch(true, null))
        }
        // statusRepository is een strikt mock (geen relaxed): elke onverwachte call
        // op upsert had de assertThrows hierboven al verstoord met een mockk-fout.
    }

    @Test
    fun `wijzigStatus gooit NotFound als bericht niet bestaat`() {
        val id = UUID.randomUUID()
        every { berichtRepository.findByBerichtId(id) } returns null

        assertThrows<NotFoundException> {
            service.wijzigStatus(id, ontvanger, BerichtStatusPatch(true, null))
        }
    }

    @Test
    fun `verwijder roept softDelete aan en eindigt zonder fout`() {
        val b = bericht()
        every { berichtRepository.findByBerichtId(b.berichtId) } returns b
        every { berichtRepository.softDelete(b.berichtId, ontvanger, any()) } returns true

        service.verwijder(b.berichtId, ontvanger)

        verify { berichtRepository.softDelete(b.berichtId, ontvanger, any()) }
    }

    @Test
    fun `verwijder gooit NotFound bij race-condition (softDelete raakt geen rijen)`() {
        val b = bericht()
        every { berichtRepository.findByBerichtId(b.berichtId) } returns b
        every { berichtRepository.softDelete(b.berichtId, ontvanger, any()) } returns false

        assertThrows<NotFoundException> { service.verwijder(b.berichtId, ontvanger) }
    }

    @Test
    fun `verwijder gooit Forbidden bij ontvanger-mismatch`() {
        val b = bericht(ontvangerOp = anderePersoon)
        every { berichtRepository.findByBerichtId(b.berichtId) } returns b

        assertThrows<ForbiddenException> { service.verwijder(b.berichtId, ontvanger) }
        // softDelete is niet gestubd; een aanroep had de assertThrows verstoord met
        // een mockk MissingAnswerException.
    }
}
