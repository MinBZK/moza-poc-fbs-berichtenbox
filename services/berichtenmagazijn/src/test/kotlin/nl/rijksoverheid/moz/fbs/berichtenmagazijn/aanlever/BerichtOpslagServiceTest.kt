package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie.BerichtValidatieService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class BerichtOpslagServiceTest {

    private val repository = mockk<BerichtRepository>(relaxed = true)
    private val bijlageRepository = mockk<BijlageRepository>(relaxed = true)
    private val validatieService = mockk<BerichtValidatieService>(relaxed = true)
    private val service = BerichtOpslagService(repository, bijlageRepository, validatieService)

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

        verify { repository.save(any<Bericht>()) }
        assertEquals(bericht, berichtSlot.captured)
    }

    @Test
    fun `opslaanBericht roept validatie aan vóór repository save`() {
        // Borgt het contract met issue #541: validatie hoort vóór persistentie.
        // Anders zou een ongeldig bericht eerst in de DB landen (bij rollback ook nog
        // ID-ruimte verbruiken) en faalt de keten op een onlogische plek.
        every { repository.save(any()) } answers { }

        service.opslaanBericht(
            afzender = "00000001003214345000",
            ontvangerType = IdentificatienummerType.BSN,
            ontvangerWaarde = "999993653",
            onderwerp = "Test",
            inhoud = "Inhoud",
            bijlagen = listOf(BijlageInvoer("doc.pdf", "application/pdf", byteArrayOf(1, 2))),
        )

        verifyOrder {
            validatieService.valideer(any(), any())
            repository.save(any())
        }
    }
}
