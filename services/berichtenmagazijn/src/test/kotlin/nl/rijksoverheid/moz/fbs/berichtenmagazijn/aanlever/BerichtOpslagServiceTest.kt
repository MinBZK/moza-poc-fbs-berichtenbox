package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieOutbox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BerichtOpslagServiceTest {

    private val repository = mockk<BerichtRepository>(relaxed = true)
    private val publicatieOutbox = mockk<PublicatieOutbox>(relaxed = true)
    private val service = BerichtOpslagService(repository, publicatieOutbox)

    @Test
    fun `opslaanBericht roept repository opslaan aan en retourneert het domeinobject`() {
        val berichtSlot = slot<Bericht>()
        every { repository.save(capture(berichtSlot)) } answers { }

        val bericht = service.opslaanBericht(
            afzender = "00000001003214345000",
            ontvangerType = IdentificatienummerType.BSN,
            ontvangerWaarde = "999993653",
            onderwerp = "Voorlopige aanslag 2026",
            inhoud = "Hierbij ontvangt u...",
        )

        assertNotNull(bericht.berichtId)
        assertNotNull(bericht.tijdstipOntvangst)
        assertEquals("00000001003214345000", bericht.afzender.waarde)
        assertEquals(IdentificatienummerType.BSN, bericht.ontvanger.type)
        assertEquals("999993653", bericht.ontvanger.waarde)
        assertEquals("Voorlopige aanslag 2026", bericht.onderwerp)
        assertEquals("Hierbij ontvangt u...", bericht.inhoud)
        // Default publicatieDatum = tijdstipOntvangst (direct publiceren).
        assertEquals(bericht.tijdstipOntvangst, bericht.publicatieDatum)

        verify { repository.save(any<Bericht>()) }
        verify { publicatieOutbox.planDeliveries(bericht.berichtId, bericht.publicatieDatum) }
        assertEquals(bericht, berichtSlot.captured)
    }

    @Test
    fun `opslaanBericht met publicatieDatum in de toekomst gebruikt die als planning`() {
        val toekomst = Instant.now().plusSeconds(3_600)
        val berichtIdSlot = slot<UUID>()
        val datumSlot = slot<Instant>()
        every { publicatieOutbox.planDeliveries(capture(berichtIdSlot), capture(datumSlot)) } returns Unit

        val bericht = service.opslaanBericht(
            afzender = "00000001003214345000",
            ontvangerType = IdentificatienummerType.BSN,
            ontvangerWaarde = "999993653",
            onderwerp = "Geplande publicatie",
            inhoud = "...",
            publicatieDatum = toekomst,
        )

        assertEquals(toekomst, bericht.publicatieDatum)
        assertNotEquals(bericht.publicatieDatum, bericht.tijdstipOntvangst)
        assertEquals(bericht.berichtId, berichtIdSlot.captured)
        assertEquals(toekomst, datumSlot.captured)
    }
}
