package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.NotFoundException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever.BijlageInvoer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.identificatie.Kvk
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.common.identificatie.Rsin
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BerichtValidatieServiceTest {

    private val profielServiceClient = mockk<ProfielServiceClient>()
    private val service = BerichtValidatieService(profielServiceClient)

    private val afzender = Oin("00000001003214345000")
    private val andereAfzender = Oin("00000001003214345999")
    private val ontvangerBsn: Identificatienummer = Bsn("999993653")
    private val ontvangerRsin: Identificatienummer = Rsin("002564440")
    private val ontvangerKvk: Identificatienummer = Kvk("12345678")
    private val ontvangerOin: Identificatienummer =
        Identificatienummer.of(IdentificatienummerType.OIN, "00000001003214345001")

    private fun maakBericht(ontvanger: Identificatienummer = ontvangerBsn): Bericht {
        val nu = Instant.now()
        return Bericht(
            berichtId = UUID.randomUUID(),
            afzender = afzender,
            ontvanger = ontvanger,
            onderwerp = "Voorlopige aanslag",
            inhoud = "Inhoud",
            tijdstipOntvangst = nu,
            publicatiedatum = nu,
        )
    }

    private fun pdfBijlage(naam: String = "doc.pdf"): BijlageInvoer =
        BijlageInvoer(naam = naam, mimeType = "application/pdf", content = byteArrayOf(1, 2, 3))

    /** Profiel met een actieve OntvangViaBerichtenbox-voorkeur voor [afzenderOin]. */
    private fun profielMetAbonnement(
        afzenderOin: String = afzender.waarde,
        waarde: String = "true",
    ): PartijResponse = PartijResponse(
        voorkeuren = listOf(
            VoorkeurResponse(
                voorkeurType = "OntvangViaBerichtenbox",
                waarde = waarde,
                scopes = listOf(
                    ScopeResponse(
                        partij = IdentificatieResponse(
                            identificatieType = "OIN",
                            identificatieNummer = afzenderOin,
                        ),
                    ),
                ),
            ),
        ),
    )

    // === MIME-validatie ===

    @Test
    fun `valideer PDF bijlage met actieve voorkeur gooit geen exception`() {
        every { profielServiceClient.getPartij(any(), any()) } returns profielMetAbonnement()

        assertDoesNotThrow {
            service.valideer(maakBericht(), listOf(pdfBijlage()))
        }
    }

    @Test
    fun `valideer niet-PDF bijlage gooit DomainValidationException met MIME-type in message`() {
        val nietPdf = BijlageInvoer(naam = "plaatje.png", mimeType = "image/png", content = byteArrayOf(1))

        val ex = assertThrows(DomainValidationException::class.java) {
            service.valideer(maakBericht(), listOf(nietPdf))
        }
        assertTrue(ex.message!!.contains("image/png"), "message moet daadwerkelijk MIME-type bevatten: ${ex.message}")
        assertTrue(ex.message!!.contains("application/pdf"), "message moet verwijzen naar verwacht type: ${ex.message}")
    }

    @Test
    fun `valideer faalt op eerste niet-PDF bijlage bij gemengde lijst`() {
        val bijlagen = listOf(
            pdfBijlage("ok.pdf"),
            BijlageInvoer(naam = "doc.docx", mimeType = "application/msword", content = byteArrayOf(1)),
            pdfBijlage("ook-ok.pdf"),
        )

        val ex = assertThrows(DomainValidationException::class.java) {
            service.valideer(maakBericht(), bijlagen)
        }
        assertTrue(ex.message!!.contains("application/msword"))
    }

    @Test
    fun `valideer controleert MIME-type vóór profielservice (fail-fast bij PNG zonder REST-call)`() {
        val nietPdf = BijlageInvoer(naam = "x.png", mimeType = "image/png", content = byteArrayOf(1))

        assertThrows(DomainValidationException::class.java) {
            service.valideer(maakBericht(), listOf(nietPdf))
        }
        verify { profielServiceClient wasNot Called }
    }

    @Test
    fun `MIME-type-check is case-sensitive — APPLICATION_PDF in hoofdletters wordt afgekeurd`() {
        val hoofdletters = BijlageInvoer(
            naam = "doc.pdf",
            mimeType = "APPLICATION/PDF",
            content = byteArrayOf(1),
        )

        assertThrows(DomainValidationException::class.java) {
            service.valideer(maakBericht(), listOf(hoofdletters))
        }
    }

    // === Abonnementscontrole — happy paths ===

    @Test
    fun `valideer BSN-ontvanger roept profielservice aan met type BSN`() {
        every { profielServiceClient.getPartij("BSN", ontvangerBsn.waarde) } returns profielMetAbonnement()

        service.valideer(maakBericht(ontvangerBsn), listOf(pdfBijlage()))

        verify(exactly = 1) { profielServiceClient.getPartij("BSN", ontvangerBsn.waarde) }
    }

    @Test
    fun `valideer RSIN-ontvanger roept profielservice aan met type RSIN`() {
        every { profielServiceClient.getPartij("RSIN", ontvangerRsin.waarde) } returns profielMetAbonnement()

        service.valideer(maakBericht(ontvangerRsin), listOf(pdfBijlage()))

        verify(exactly = 1) { profielServiceClient.getPartij("RSIN", ontvangerRsin.waarde) }
    }

    @Test
    fun `valideer KVK-ontvanger roept profielservice aan met type KVK`() {
        every { profielServiceClient.getPartij("KVK", ontvangerKvk.waarde) } returns profielMetAbonnement()

        service.valideer(maakBericht(ontvangerKvk), listOf(pdfBijlage()))

        verify(exactly = 1) { profielServiceClient.getPartij("KVK", ontvangerKvk.waarde) }
    }

    @Test
    fun `valideer OIN-ontvanger (organisatie-naar-organisatie) roept profielservice NIET aan`() {
        assertDoesNotThrow {
            service.valideer(maakBericht(ontvangerOin), listOf(pdfBijlage()))
        }
        verify { profielServiceClient wasNot Called }
    }

    @Test
    fun `valideer accepteert waarde 'ja' (Nederlands) als actief`() {
        every { profielServiceClient.getPartij(any(), any()) } returns
            profielMetAbonnement(waarde = "ja")

        assertDoesNotThrow {
            service.valideer(maakBericht(), listOf(pdfBijlage()))
        }
    }

    @Test
    fun `valideer accepteert waarde 'TRUE' case-insensitive als actief`() {
        every { profielServiceClient.getPartij(any(), any()) } returns
            profielMetAbonnement(waarde = "TRUE")

        assertDoesNotThrow {
            service.valideer(maakBericht(), listOf(pdfBijlage()))
        }
    }

    // === Abonnementscontrole — geweigerd ===

    @Test
    fun `valideer gooit Geweigerd als partij geen voorkeuren heeft`() {
        every { profielServiceClient.getPartij(any(), any()) } returns PartijResponse()

        assertThrows(ToestemmingGeweigerdException::class.java) {
            service.valideer(maakBericht(), listOf(pdfBijlage()))
        }
    }

    @Test
    fun `valideer gooit Geweigerd als voorkeur scope naar andere afzender wijst`() {
        every { profielServiceClient.getPartij(any(), any()) } returns
            profielMetAbonnement(afzenderOin = andereAfzender.waarde)

        assertThrows(ToestemmingGeweigerdException::class.java) {
            service.valideer(maakBericht(), listOf(pdfBijlage()))
        }
    }

    @Test
    fun `valideer gooit Geweigerd als voorkeur geen enkele scope heeft`() {
        every { profielServiceClient.getPartij(any(), any()) } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(voorkeurType = "OntvangViaBerichtenbox", waarde = "true", scopes = emptyList()),
            ),
        )

        assertThrows(ToestemmingGeweigerdException::class.java) {
            service.valideer(maakBericht(), listOf(pdfBijlage()))
        }
    }

    @Test
    fun `valideer gooit Geweigerd als voorkeur waarde 'false' is`() {
        every { profielServiceClient.getPartij(any(), any()) } returns
            profielMetAbonnement(waarde = "false")

        assertThrows(ToestemmingGeweigerdException::class.java) {
            service.valideer(maakBericht(), listOf(pdfBijlage()))
        }
    }

    @Test
    fun `valideer gooit Geweigerd als waarde null is`() {
        every { profielServiceClient.getPartij(any(), any()) } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = null,
                    scopes = listOf(
                        ScopeResponse(partij = IdentificatieResponse("OIN", afzender.waarde)),
                    ),
                ),
            ),
        )

        assertThrows(ToestemmingGeweigerdException::class.java) {
            service.valideer(maakBericht(), listOf(pdfBijlage()))
        }
    }

    @Test
    fun `valideer gooit Geweigerd als alleen andere voorkeur-typen aanwezig zijn`() {
        // Partij heeft WebsiteTaal-voorkeur maar geen OntvangViaBerichtenbox.
        every { profielServiceClient.getPartij(any(), any()) } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "WebsiteTaal",
                    waarde = "nl",
                    scopes = listOf(ScopeResponse(partij = IdentificatieResponse("OIN", afzender.waarde))),
                ),
            ),
        )

        assertThrows(ToestemmingGeweigerdException::class.java) {
            service.valideer(maakBericht(), listOf(pdfBijlage()))
        }
    }

    @Test
    fun `valideer accepteert wanneer meerdere scopes aanwezig zijn en één matcht`() {
        every { profielServiceClient.getPartij(any(), any()) } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = "true",
                    scopes = listOf(
                        ScopeResponse(partij = IdentificatieResponse("OIN", andereAfzender.waarde)),
                        ScopeResponse(partij = IdentificatieResponse("OIN", afzender.waarde)),
                    ),
                ),
            ),
        )

        assertDoesNotThrow {
            service.valideer(maakBericht(), listOf(pdfBijlage()))
        }
    }

    @Test
    fun `valideer negeert scope zonder partij (bv alleen-dienst-scope)`() {
        // Een scope met alleen `dienst` en geen `partij` mag de afzender-check niet bedienen,
        // anders zou een willekeurige dienst onbedoeld als toestemming gelden.
        every { profielServiceClient.getPartij(any(), any()) } returns PartijResponse(
            voorkeuren = listOf(
                VoorkeurResponse(
                    voorkeurType = "OntvangViaBerichtenbox",
                    waarde = "true",
                    scopes = listOf(
                        ScopeResponse(partij = null, dienst = DienstResponse(id = 42, beschrijving = "X")),
                    ),
                ),
            ),
        )

        assertThrows(ToestemmingGeweigerdException::class.java) {
            service.valideer(maakBericht(), listOf(pdfBijlage()))
        }
    }

    @Test
    fun `valideer gooit Geweigerd bij 404 van profielservice (onbekende partij, fail-closed)`() {
        every { profielServiceClient.getPartij(any(), any()) } throws NotFoundException()

        assertThrows(ToestemmingGeweigerdException::class.java) {
            service.valideer(maakBericht(), listOf(pdfBijlage()))
        }
    }

    @Test
    fun `valideer laat andere HTTP-fouten doorvloeien (geen swallow)`() {
        // Een 5xx is een infrastructuurfout — die moet de circuit breaker triggeren,
        // niet stilzwijgend als toestemmings-weigering worden behandeld.
        every { profielServiceClient.getPartij(any(), any()) } throws RuntimeException("upstream down")

        assertThrows(RuntimeException::class.java) {
            service.valideer(maakBericht(), listOf(pdfBijlage()))
        }
    }

    @Test
    fun `valideer met lege bijlagenlijst en actief abonnement gooit geen exception`() {
        every { profielServiceClient.getPartij(any(), any()) } returns profielMetAbonnement()

        assertDoesNotThrow {
            service.valideer(maakBericht(), emptyList())
        }
    }
}
