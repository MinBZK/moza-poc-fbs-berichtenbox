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
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

    private fun valideer(
        onderwerp: String = "Voorlopige aanslag 2026",
        inhoud: String = "Hierbij ontvangt u...",
        publicatietijdstip: Instant? = null,
        bijlagen: List<BijlageInvoer> = emptyList(),
    ): Bericht = service.valideerAanlevering(
        afzender = "00000001003214345000",
        ontvangerType = IdentificatienummerType.BSN,
        ontvangerWaarde = "999993653",
        onderwerp = onderwerp,
        inhoud = inhoud,
        publicatietijdstip = publicatietijdstip,
        bijlagen = bijlagen,
    )

    @Test
    fun `slaBerichtOp roept repository opslaan aan met het gevalideerde domeinobject`() {
        val berichtSlot = slot<Bericht>()
        every { repository.save(capture(berichtSlot)) } answers { }

        val bericht = valideer()
        service.slaBerichtOp(bericht)

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

        val bericht = valideer(onderwerp = "Geplande publicatie", inhoud = "...", publicatietijdstip = toekomst)
        service.slaBerichtOp(bericht)

        assertEquals(toekomst, bericht.publicatietijdstip)
        assertNotEquals(bericht.publicatietijdstip, bericht.tijdstipOntvangst)
        assertEquals(bericht.berichtId, berichtIdSlot.captured)
        assertEquals(toekomst, datumSlot.captured)
    }

    @Test
    fun `valideerAanlevering valideert zonder iets op te slaan`() {
        // Borgt het contract met issue #541: validatie hoort vóór persistentie. Doordat
        // valideerAanlevering en slaBerichtOp gescheiden zijn, kan de aanroeper daar de
        // logregel tussen schrijven — en blijft een ongeldig bericht sowieso uit de DB.
        every { repository.save(any()) } answers { }
        val bijlagen = listOf(BijlageInvoer("doc.pdf", "application/pdf", byteArrayOf(1, 2)))

        val bericht = valideer(onderwerp = "Test", inhoud = "Inhoud", bijlagen = bijlagen)

        verify { validatieService.valideer(bericht, bijlagen) }
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { publicatieOutbox.planDeliveries(any(), any()) }

        service.slaBerichtOp(bericht, bijlagen)

        verifyOrder {
            validatieService.valideer(bericht, bijlagen)
            repository.save(bericht)
        }
    }

    @Test
    fun `valideerAanlevering laat een validatiefout door en slaat niets op`() {
        every { validatieService.valideer(any(), any()) } throws
            DomainValidationException("Bijlage mimeType moet application/pdf zijn")

        assertThrows<DomainValidationException> {
            valideer(bijlagen = listOf(BijlageInvoer("doc.txt", "text/plain", byteArrayOf(1))))
        }

        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { publicatieOutbox.planDeliveries(any(), any()) }
    }
}
