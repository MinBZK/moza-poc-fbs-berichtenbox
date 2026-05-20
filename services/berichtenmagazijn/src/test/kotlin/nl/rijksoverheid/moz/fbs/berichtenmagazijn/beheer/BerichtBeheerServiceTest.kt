package nl.rijksoverheid.moz.fbs.berichtenmagazijn.beheer

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtMetVerwijderdOp
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
        publicatiedatum = Instant.parse("2026-05-13T10:00:00Z"),
    )

    @Test
    fun `wijzigStatus geeft patch door aan repository en retourneert verrijkt bericht`() {
        val b = bericht()
        val patch = BerichtStatusPatch(gelezen = true, map = "archief")
        val nieuweStatus = BerichtStatus(gelezen = true, map = "archief", gewijzigdOp = Instant.now())
        val patchSlot = slot<BerichtStatusPatch>()
        every { berichtRepository.findIncludingDeleted(b.berichtId) } returns BerichtMetVerwijderdOp(b, null)
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
        every { berichtRepository.findIncludingDeleted(b.berichtId) } returns BerichtMetVerwijderdOp(b, null)

        assertThrows<ForbiddenException> {
            service.wijzigStatus(b.berichtId, ontvanger, BerichtStatusPatch(true, null))
        }
        // statusRepository is een strikt mock (geen relaxed): elke onverwachte call
        // op upsert had de assertThrows hierboven al verstoord met een mockk-fout.
    }

    @Test
    fun `wijzigStatus gooit NotFound als bericht niet bestaat`() {
        val id = UUID.randomUUID()
        every { berichtRepository.findIncludingDeleted(id) } returns null

        assertThrows<NotFoundException> {
            service.wijzigStatus(id, ontvanger, BerichtStatusPatch(true, null))
        }
    }

    @Test
    fun `wijzigStatus gooit NotFound op eigen soft-deleted bericht`() {
        val b = bericht()
        every { berichtRepository.findIncludingDeleted(b.berichtId) } returns
            BerichtMetVerwijderdOp(b, Instant.parse("2026-05-13T11:00:00Z"))

        assertThrows<NotFoundException> {
            service.wijzigStatus(b.berichtId, ontvanger, BerichtStatusPatch(true, null))
        }
    }

    @Test
    fun `wijzigStatus gooit Forbidden op andermans soft-deleted bericht (geen 404-leak)`() {
        val b = bericht(ontvangerOp = anderePersoon)
        every { berichtRepository.findIncludingDeleted(b.berichtId) } returns
            BerichtMetVerwijderdOp(b, Instant.parse("2026-05-13T11:00:00Z"))

        assertThrows<ForbiddenException> {
            service.wijzigStatus(b.berichtId, ontvanger, BerichtStatusPatch(true, null))
        }
    }

    @Test
    fun `verwijder roept softDelete aan en eindigt zonder fout`() {
        val b = bericht()
        every { berichtRepository.findIncludingDeleted(b.berichtId) } returns BerichtMetVerwijderdOp(b, null)
        every { berichtRepository.softDelete(b.berichtId, ontvanger, any()) } returns true

        service.verwijder(b.berichtId, ontvanger)

        verify { berichtRepository.softDelete(b.berichtId, ontvanger, any()) }
    }

    @Test
    fun `verwijder is no-op (geen mutatie, geen 404) bij al-verwijderd bericht`() {
        // RFC 9110 §9.3.5: een tweede DELETE door dezelfde rechtmatige ontvanger
        // mag niet 404 geven (dat zou de client doen twijfelen of het bericht ooit
        // bestond), maar simpelweg succesvol zijn zonder mutatie.
        val b = bericht()
        every { berichtRepository.findIncludingDeleted(b.berichtId) } returns
            BerichtMetVerwijderdOp(b, Instant.parse("2026-05-13T11:00:00Z"))

        service.verwijder(b.berichtId, ontvanger)

        // softDelete mag niet aangeroepen worden — de mock is strikt zonder
        // antwoord, dus een aanroep zou een MockKException werpen.
    }

    @Test
    fun `verwijder logt race-warning bij softDelete=false met bevestigde verwijdering door dezelfde ontvanger`() {
        // Race: tussen eerste find en softDelete heeft een concurrente DELETE al
        // verwijderd. De service re-checked findIncludingDeleted en ziet
        // verwijderdOp != null + dezelfde ontvanger → no-op (204).
        val b = bericht()
        every { berichtRepository.findIncludingDeleted(b.berichtId) } returnsMany listOf(
            BerichtMetVerwijderdOp(b, null),
            BerichtMetVerwijderdOp(b, Instant.parse("2026-05-13T11:00:00Z")),
        )
        every { berichtRepository.softDelete(b.berichtId, ontvanger, any()) } returns false

        service.verwijder(b.berichtId, ontvanger)
    }

    @Test
    fun `verwijder faalt hard als softDelete=false en bericht is hard-verdwenen`() {
        // Onverwachte hard-delete tussen eerste find en re-check: dit is geen
        // race door dezelfde ontvanger maar een onbekende mutatie. Moet hard
        // falen i.p.v. silently 204 — anders kan een gestolen DELETE de
        // tweede client misleiden.
        val b = bericht()
        every { berichtRepository.findIncludingDeleted(b.berichtId) } returnsMany listOf(
            BerichtMetVerwijderdOp(b, null),
            null,
        )
        every { berichtRepository.softDelete(b.berichtId, ontvanger, any()) } returns false

        assertThrows<IllegalStateException> { service.verwijder(b.berichtId, ontvanger) }
    }

    @Test
    fun `verwijder faalt als softDelete=false maar bericht niet verwijderd blijkt`() {
        // Pathologisch: race-tak meldt 0 rijen geraakt, maar verwijderdOp is null.
        // Wijst op DB-inconsistentie of een toekomstige bug in WHERE-clause.
        // Moet hard falen — geen silent 204.
        val b = bericht()
        every { berichtRepository.findIncludingDeleted(b.berichtId) } returns BerichtMetVerwijderdOp(b, null)
        every { berichtRepository.softDelete(b.berichtId, ontvanger, any()) } returns false

        assertThrows<IllegalStateException> { service.verwijder(b.berichtId, ontvanger) }
    }

    @Test
    fun `verwijder gooit Forbidden bij ontvanger-mismatch`() {
        val b = bericht(ontvangerOp = anderePersoon)
        every { berichtRepository.findIncludingDeleted(b.berichtId) } returns BerichtMetVerwijderdOp(b, null)

        assertThrows<ForbiddenException> { service.verwijder(b.berichtId, ontvanger) }
        // softDelete is niet gestubd; een aanroep had de assertThrows verstoord met
        // een mockk MissingAnswerException.
    }

    @Test
    fun `verwijder gooit NotFound als bericht niet bestaat (ook niet soft-deleted)`() {
        val id = UUID.randomUUID()
        every { berichtRepository.findIncludingDeleted(id) } returns null

        assertThrows<NotFoundException> { service.verwijder(id, ontvanger) }
    }
}
