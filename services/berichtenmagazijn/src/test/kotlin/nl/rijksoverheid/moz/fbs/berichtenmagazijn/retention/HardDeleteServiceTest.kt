package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.Period
import java.util.UUID

class HardDeleteServiceTest {

    private val ops = mockk<HardDeleteTransactionalOps>(relaxed = true)
    private val ldv = mockk<HardDeleteLdvLogger>(relaxed = true)
    private val config = mockk<RetentionConfig> {
        every { minimaleLeeftijd() } returns Period.ofYears(7)
        every { minimaleSoftDeleteLeeftijd() } returns Period.ofYears(7)
        every { batchGrootte() } returns 2
        every { cron() } returns "0 0 3 * * ?"
    }

    private val service = HardDeleteService(ops, ldv, config)

    private fun kandidaat(id: Long = 1L) = HardDeleteCandidaat(
        id = id,
        berichtId = UUID.randomUUID(),
        ontvangerType = "BSN",
        ontvangerWaarde = "999993653",
        tijdstipOntvangst = Instant.now().minusSeconds(3000L * 86400),
        verwijderdOp = Instant.now().minusSeconds(3000L * 86400),
    )

    @Test
    fun `lege claim — geen deletes`() {
        every { ops.claim(any(), any(), any()) } returns emptyList()

        val result = service.run()

        assertEquals(0, result.totaalVerwijderd)
        verify(exactly = 0) { ops.deleteOne(any()) }
        verify(exactly = 0) { ldv.logHardDelete(any()) }
    }

    @Test
    fun `claim van 2 — beide verwijderd en gelogd`() {
        val a = kandidaat(1L)
        val b = kandidaat(2L)
        every { ops.deleteOne(any()) } returns 1
        every { ops.claim(any(), any(), any()) } returnsMany listOf(
            listOf(a, b),
            emptyList(),
        )

        val result = service.run()

        assertEquals(2, result.totaalVerwijderd)
        verify(exactly = 1) { ops.deleteOne(a) }
        verify(exactly = 1) { ops.deleteOne(b) }
        verify(exactly = 1) { ldv.logHardDelete(a) }
        verify(exactly = 1) { ldv.logHardDelete(b) }
    }

    @Test
    fun `delete-failure op één bericht — overige worden verwerkt`() {
        val a = kandidaat(1L)
        val b = kandidaat(2L)
        every { ops.deleteOne(a) } throws RuntimeException("simuleer FK-violation")
        every { ops.deleteOne(b) } returns 1
        every { ops.claim(any(), any(), any()) } returnsMany listOf(
            listOf(a, b),
            emptyList(),
        )

        val result = service.run()

        assertEquals(1, result.totaalVerwijderd)
        assertEquals(1, result.fouten)
        verify(exactly = 1) { ldv.logHardDelete(b) }
        verify(exactly = 0) { ldv.logHardDelete(a) }
    }

    @Test
    fun `ldv-failure — bericht blijft als verwijderd geteld`() {
        val a = kandidaat(1L)
        every { ops.deleteOne(a) } returns 1
        every { ldv.logHardDelete(a) } throws RuntimeException("LDV down")
        every { ops.claim(any(), any(), any()) } returnsMany listOf(
            listOf(a),
            emptyList(),
        )

        val result = service.run()

        assertEquals(1, result.totaalVerwijderd)
        assertEquals(1, result.ldvFouten)
    }

    @Test
    fun `volle batch leidt tot tweede claim-ronde`() {
        val batchVol = listOf(kandidaat(1L), kandidaat(2L))
        every { ops.deleteOne(any()) } returns 1
        every { ops.claim(any(), any(), 2) } returnsMany listOf(
            batchVol,
            emptyList(),
        )

        val result = service.run()

        assertEquals(2, result.totaalVerwijderd)
        verify(exactly = 2) { ops.claim(any(), any(), 2) }
    }

    @Test
    fun `niet-volle batch stopt de loop`() {
        val onvol = listOf(kandidaat(1L))
        every { ops.deleteOne(any()) } returns 1
        every { ops.claim(any(), any(), 2) } returns onvol

        val result = service.run()

        assertEquals(1, result.totaalVerwijderd)
        verify(exactly = 1) { ops.claim(any(), any(), 2) }
    }
}
