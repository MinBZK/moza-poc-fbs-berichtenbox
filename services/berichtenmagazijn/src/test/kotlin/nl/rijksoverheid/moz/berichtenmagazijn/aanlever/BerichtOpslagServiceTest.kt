package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class BerichtOpslagServiceTest {

    private val repository = mockk<BerichtRepository>(relaxed = true)
    private val service = BerichtOpslagService(repository)

    @Test
    fun `opslaanBericht roept repository opslaan aan en retourneert het domeinobject`() {
        val berichtSlot = slot<Bericht>()
        every { repository.opslaan(capture(berichtSlot)) } answers { }

        val bericht = service.opslaanBericht(
            afzender = "00000001003214345000",
            ontvanger = "999993653",
            onderwerp = "Voorlopige aanslag 2026",
            inhoud = "Hierbij ontvangt u...",
        )

        assertNotNull(bericht.berichtId)
        assertNotNull(bericht.tijdstip)
        assertEquals("00000001003214345000", bericht.afzender.waarde)
        assertEquals("999993653", bericht.ontvanger.waarde)
        assertEquals("Voorlopige aanslag 2026", bericht.onderwerp)
        assertEquals("Hierbij ontvangt u...", bericht.inhoud)

        verify { repository.opslaan(any<Bericht>()) }
        assertEquals(bericht, berichtSlot.captured)
    }
}
