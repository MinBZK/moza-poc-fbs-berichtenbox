package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtEntity
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BerichtOpslagServiceTest {

    private val repository = mockk<BerichtRepository>(relaxed = true)
    private val service = BerichtOpslagService(repository)

    @Test
    fun `opslaanBericht persist entity en retourneert domeinobject met gegenereerd id en tijdstip`() {
        val entitySlot = slot<BerichtEntity>()
        every { repository.persist(capture(entitySlot)) } answers { }

        val bericht = service.opslaanBericht(
            afzender = "00000001003214345000",
            ontvanger = "999993653",
            onderwerp = "Voorlopige aanslag 2026",
            inhoud = "Hierbij ontvangt u...",
        )

        assertNotNull(bericht.berichtId)
        assertNotNull(bericht.tijdstip)
        assertEquals("00000001003214345000", bericht.afzender)
        assertEquals("999993653", bericht.ontvanger)
        assertEquals("Voorlopige aanslag 2026", bericht.onderwerp)
        assertEquals("Hierbij ontvangt u...", bericht.inhoud)

        verify { repository.persist(any<BerichtEntity>()) }
        assertEquals(bericht.berichtId, entitySlot.captured.berichtId)
        assertEquals(bericht.afzender, entitySlot.captured.afzender)
    }

    @Test
    fun `opslaanBericht genereert uniek berichtId per aanroep`() {
        every { repository.persist(any<BerichtEntity>()) } answers { }

        val bericht1 = service.opslaanBericht("a", "b", "c", "d")
        val bericht2 = service.opslaanBericht("a", "b", "c", "d")

        assertTrue(bericht1.berichtId != bericht2.berichtId)
    }
}
