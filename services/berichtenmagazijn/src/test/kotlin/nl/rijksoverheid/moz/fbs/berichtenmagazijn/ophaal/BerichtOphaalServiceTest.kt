package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatus
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatusRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bijlage
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageMetadata
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Identificatienummer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.PagedBerichten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class BerichtOphaalServiceTest {

    private val berichtRepository = mockk<BerichtRepository>()
    private val bijlageRepository = mockk<BijlageRepository>(relaxed = true)
    private val statusRepository = mockk<BerichtStatusRepository>(relaxed = true)
    private val service = BerichtOphaalService(berichtRepository, bijlageRepository, statusRepository)

    private val ontvanger: Identificatienummer = Bsn("999993653")
    private val anderePersoon: Identificatienummer = Bsn("123456782")

    private fun bericht(ontvangerOp: Identificatienummer = ontvanger): Bericht = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = Oin("00000001003214345000"),
        ontvanger = ontvangerOp,
        onderwerp = "Voorlopige aanslag 2026",
        inhoud = "Inhoud",
        tijdstipOntvangst = Instant.parse("2026-05-13T10:00:00Z"),
        publicatiedatum = Instant.parse("2026-05-13T10:00:00Z"),
    )

    @Test
    fun `haalBerichtOp verrijkt het bericht met bijlagen-metadata en status`() {
        val b = bericht()
        val meta = listOf(BijlageMetadata(UUID.randomUUID(), "aanslag.pdf", "application/pdf"))
        val status = BerichtStatus(gelezen = true, map = "archief", gewijzigdOp = Instant.now())
        every { berichtRepository.findByBerichtId(b.berichtId) } returns b
        every { bijlageRepository.metadataVoorBericht(b.berichtId) } returns meta
        every { statusRepository.findByBerichtId(b.berichtId) } returns status

        val resultaat = service.haalBerichtOp(b.berichtId, ontvanger)

        assertEquals(meta, resultaat.bijlagen)
        assertEquals(status, resultaat.status)
    }

    @Test
    fun `haalBerichtOp gooit NotFound als bericht niet bestaat of soft-deleted is`() {
        val id = UUID.randomUUID()
        every { berichtRepository.findByBerichtId(id) } returns null

        assertThrows<NotFoundException> { service.haalBerichtOp(id, ontvanger) }
    }

    @Test
    fun `haalBerichtOp gooit Forbidden als ontvanger niet matcht`() {
        val b = bericht(ontvangerOp = anderePersoon)
        every { berichtRepository.findByBerichtId(b.berichtId) } returns b

        assertThrows<ForbiddenException> { service.haalBerichtOp(b.berichtId, ontvanger) }
    }

    @Test
    fun `haalBijlageOp valideert eerst toegang tot het bericht`() {
        val b = bericht(ontvangerOp = anderePersoon)
        val bijlageId = UUID.randomUUID()
        every { berichtRepository.findByBerichtId(b.berichtId) } returns b

        assertThrows<ForbiddenException> { service.haalBijlageOp(b.berichtId, bijlageId, ontvanger) }
        verify(exactly = 0) { bijlageRepository.findByBerichtIdEnBijlageId(any(), any()) }
    }

    @Test
    fun `haalBijlageOp gooit NotFound als bijlage niet bij het bericht hoort`() {
        val b = bericht()
        val bijlageId = UUID.randomUUID()
        every { berichtRepository.findByBerichtId(b.berichtId) } returns b
        every { bijlageRepository.findByBerichtIdEnBijlageId(b.berichtId, bijlageId) } returns null

        assertThrows<NotFoundException> { service.haalBijlageOp(b.berichtId, bijlageId, ontvanger) }
    }

    @Test
    fun `haalBijlageOp retourneert de bytes als alles klopt`() {
        val b = bericht()
        val bijlageId = UUID.randomUUID()
        val bijlage = Bijlage(
            bijlageId = bijlageId,
            berichtId = b.berichtId,
            naam = "aanslag.pdf",
            mimeType = "application/pdf",
            content = byteArrayOf(0x25, 0x50, 0x44, 0x46), // %PDF
        )
        every { berichtRepository.findByBerichtId(b.berichtId) } returns b
        every { bijlageRepository.findByBerichtIdEnBijlageId(b.berichtId, bijlageId) } returns bijlage

        val resultaat = service.haalBijlageOp(b.berichtId, bijlageId, ontvanger)

        assertEquals(bijlage, resultaat)
    }

    @Test
    fun `lijst voegt per bericht de status toe`() {
        val b1 = bericht()
        val b2 = bericht()
        val pagina = PagedBerichten(berichten = listOf(b1, b2), page = 0, pageSize = 20, totalElements = 2L)
        val status1 = BerichtStatus(gelezen = true, map = null, gewijzigdOp = Instant.now())
        every { berichtRepository.lijstVoorOntvanger(ontvanger, null, 0, 20) } returns pagina
        every { statusRepository.findByBerichtIds(listOf(b1.berichtId, b2.berichtId)) } returns
            mapOf(b1.berichtId to status1)

        val resultaat = service.lijst(ontvanger, afzender = null, page = 0, pageSize = 20)

        assertEquals(2L, resultaat.totalElements)
        assertEquals(status1, resultaat.berichten[0].status)
        assertNull(resultaat.berichten[1].status)
    }
}
