package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.Called
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever.NieuweBijlage
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Identificatienummer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Kvk
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Rsin
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BerichtValidatieServiceTest {

    private val toestemmingControle = mockk<ToestemmingControle>()
    private val service = BerichtValidatieService(toestemmingControle)

    private val afzender = Oin("00000001003214345000")
    private val ontvangerBsn: Identificatienummer = Bsn("999993653")
    private val ontvangerRsin: Identificatienummer = Rsin("002564440")
    private val ontvangerKvk: Identificatienummer = Kvk("12345678")
    private val ontvangerOin: Identificatienummer =
        Identificatienummer.of(IdentificatienummerType.OIN, "00000001003214345001")

    private fun maakBericht(ontvanger: Identificatienummer = ontvangerBsn): Bericht = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = afzender,
        ontvanger = ontvanger,
        onderwerp = "Voorlopige aanslag",
        inhoud = "Inhoud",
        tijdstipOntvangst = Instant.now(),
    )

    private fun pdfBijlage(naam: String = "doc.pdf"): NieuweBijlage =
        NieuweBijlage(naam = naam, mimeType = "application/pdf", content = byteArrayOf(1, 2, 3))

    @Test
    fun `valideer PDF bijlage met toestemming true gooit geen exception`() {
        every { toestemmingControle.controleer(any()) } returns ToestemmingAntwoord(toegestaan = true)

        assertDoesNotThrow {
            service.valideer(maakBericht(), listOf(pdfBijlage()))
        }
    }

    @Test
    fun `valideer niet-PDF bijlage gooit DomainValidationException met MIME-type in message`() {
        every { toestemmingControle.controleer(any()) } returns ToestemmingAntwoord(toegestaan = true)
        val nietPdf = NieuweBijlage(naam = "plaatje.png", mimeType = "image/png", content = byteArrayOf(1))

        val ex = assertThrows(DomainValidationException::class.java) {
            service.valideer(maakBericht(), listOf(nietPdf))
        }
        assertTrue(ex.message!!.contains("image/png"), "message moet daadwerkelijk MIME-type bevatten: ${ex.message}")
        assertTrue(ex.message!!.contains("application/pdf"), "message moet verwijzen naar verwacht type: ${ex.message}")
    }

    @Test
    fun `valideer faalt op eerste niet-PDF bijlage bij gemengde lijst`() {
        every { toestemmingControle.controleer(any()) } returns ToestemmingAntwoord(toegestaan = true)
        val bijlagen = listOf(
            pdfBijlage("ok.pdf"),
            NieuweBijlage(naam = "doc.docx", mimeType = "application/msword", content = byteArrayOf(1)),
            pdfBijlage("ook-ok.pdf"),
        )

        val ex = assertThrows(DomainValidationException::class.java) {
            service.valideer(maakBericht(), bijlagen)
        }
        assertTrue(ex.message!!.contains("application/msword"), "verwacht eerste niet-PDF in message: ${ex.message}")
    }

    @Test
    fun `valideer BSN-ontvanger zonder toestemming gooit ToestemmingGeweigerdException`() {
        every { toestemmingControle.controleer(any()) } returns ToestemmingAntwoord(toegestaan = false)

        assertThrows(ToestemmingGeweigerdException::class.java) {
            service.valideer(maakBericht(ontvangerBsn), listOf(pdfBijlage()))
        }
    }

    @Test
    fun `valideer RSIN-ontvanger roept profiel-service aan met type RSIN`() {
        every { toestemmingControle.controleer(any()) } returns ToestemmingAntwoord(toegestaan = false)

        assertThrows(ToestemmingGeweigerdException::class.java) {
            service.valideer(maakBericht(ontvangerRsin), listOf(pdfBijlage()))
        }
        verify {
            toestemmingControle.controleer(
                ToestemmingVerzoek(
                    ontvangerType = "RSIN",
                    ontvangerWaarde = ontvangerRsin.waarde,
                    afzender = afzender.waarde,
                ),
            )
        }
    }

    @Test
    fun `valideer KVK-ontvanger roept profiel-service aan met type KVK`() {
        every { toestemmingControle.controleer(any()) } returns ToestemmingAntwoord(toegestaan = false)

        assertThrows(ToestemmingGeweigerdException::class.java) {
            service.valideer(maakBericht(ontvangerKvk), listOf(pdfBijlage()))
        }
        verify {
            toestemmingControle.controleer(
                ToestemmingVerzoek(
                    ontvangerType = "KVK",
                    ontvangerWaarde = ontvangerKvk.waarde,
                    afzender = afzender.waarde,
                ),
            )
        }
    }

    @Test
    fun `valideer BSN-ontvanger roept profiel-service aan met type BSN`() {
        every { toestemmingControle.controleer(any()) } returns ToestemmingAntwoord(toegestaan = true)

        service.valideer(maakBericht(ontvangerBsn), listOf(pdfBijlage()))

        verify {
            toestemmingControle.controleer(
                ToestemmingVerzoek(
                    ontvangerType = "BSN",
                    ontvangerWaarde = ontvangerBsn.waarde,
                    afzender = afzender.waarde,
                ),
            )
        }
    }

    @Test
    fun `valideer OIN-ontvanger (organisatie-naar-organisatie) roept profiel-service NIET aan`() {
        assertDoesNotThrow {
            service.valideer(maakBericht(ontvangerOin), listOf(pdfBijlage()))
        }
        verify { toestemmingControle wasNot Called }
    }

    @Test
    fun `valideer met lege bijlagenlijst en toestemming true gooit geen exception`() {
        every { toestemmingControle.controleer(any()) } returns ToestemmingAntwoord(toegestaan = true)

        assertDoesNotThrow {
            service.valideer(maakBericht(), emptyList())
        }
    }

    @Test
    fun `valideer controleert MIME-type vóór toestemming (fail-fast bij PNG zonder REST-call)`() {
        // Bewaakt orde: een ongeldige bijlage zou geen onnodige (mogelijk kostbare)
        // call naar de Profiel Service mogen veroorzaken.
        val nietPdf = NieuweBijlage(naam = "x.png", mimeType = "image/png", content = byteArrayOf(1))

        assertThrows(DomainValidationException::class.java) {
            service.valideer(maakBericht(), listOf(nietPdf))
        }
        verify { toestemmingControle wasNot Called }
    }

    @Test
    fun `MIME-type-check is case-sensitive — APPLICATION_PDF in hoofdletters wordt afgekeurd`() {
        // RFC 6838: media-type-namen zijn case-insensitive; toch handhaven we lowercase
        // omdat dat de canonical form is en de OpenAPI-spec dat ook eist. Dit voorkomt
        // dat een client met "APPLICATION/PDF" stil door de check glipt en straks
        // mismatch met header-vergelijkingen elders veroorzaakt.
        every { toestemmingControle.controleer(any()) } returns ToestemmingAntwoord(toegestaan = true)
        val hoofdletters = NieuweBijlage(
            naam = "doc.pdf",
            mimeType = "APPLICATION/PDF",
            content = byteArrayOf(1),
        )

        assertThrows(DomainValidationException::class.java) {
            service.valideer(maakBericht(), listOf(hoofdletters))
        }
    }
}
