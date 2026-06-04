package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieOutbox
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie.BerichtValidatieService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BerichtOpslagServiceTest {

    private val repository = mockk<BerichtRepository>(relaxed = true)
    private val bijlageRepository = mockk<BijlageRepository>(relaxed = true)
    private val validatieService = mockk<BerichtValidatieService>(relaxed = true)
    private val publicatieOutbox = mockk<PublicatieOutbox>(relaxed = true)
    private val service = BerichtOpslagService(
        repository,
        bijlageRepository,
        validatieService,
        publicatieOutbox,
        java.time.Clock.systemUTC(),
    )

    @Test
    fun `slaBerichtOp roept repository opslaan aan en retourneert het domeinobject`() {
        val berichtSlot = slot<Bericht>()
        every { repository.save(capture(berichtSlot)) } answers { }

        val bericht = service.slaBerichtOp(
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
        // Default publicatietijdstip = tijdstipOntvangst (direct publiceren).
        assertEquals(bericht.tijdstipOntvangst, bericht.publicatietijdstip)

        verify { repository.save(any<Bericht>()) }
        verify { publicatieOutbox.planDeliveries(bericht.berichtId, bericht.publicatietijdstip) }
        assertEquals(bericht, berichtSlot.captured)
    }

    @Test
    fun `slaBerichtOp met publicatietijdstip in de toekomst gebruikt die als planning`() {
        val toekomst = Instant.now().plusSeconds(3_600)
        val berichtIdSlot = slot<UUID>()
        val datumSlot = slot<Instant>()
        every { publicatieOutbox.planDeliveries(capture(berichtIdSlot), capture(datumSlot)) } returns Unit

        val bericht = service.slaBerichtOp(
            afzender = "00000001003214345000",
            ontvangerType = IdentificatienummerType.BSN,
            ontvangerWaarde = "999993653",
            onderwerp = "Geplande publicatie",
            inhoud = "...",
            publicatietijdstip = toekomst,
        )

        assertEquals(toekomst, bericht.publicatietijdstip)
        assertNotEquals(bericht.publicatietijdstip, bericht.tijdstipOntvangst)
        assertEquals(bericht.berichtId, berichtIdSlot.captured)
        assertEquals(toekomst, datumSlot.captured)
    }

    @Test
    fun `slaBerichtOp roept validatie aan vóór repository save`() {
        // Borgt het contract met issue #541: validatie hoort vóór persistentie.
        // Anders zou een ongeldig bericht eerst in de DB landen (bij rollback ook nog
        // ID-ruimte verbruiken) en faalt de keten op een onlogische plek.
        every { repository.save(any()) } answers { }

        service.slaBerichtOp(
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
