package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.StartupEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

    private val registry = SimpleMeterRegistry()

    private val service = HardDeleteService(ops, ldv, config, HardDeleteMeting(registry))

    private fun gauge(naam: String): Double = registry.get(naam).gauge().value()

    private fun teller(naam: String, vararg tags: String): Double = registry.get(naam).tags(*tags).counter().count()

    private fun kandidaat(id: Long = 1L) = HardDeleteKandidaat(
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

        val logboek = SimpleMeterRegistry()

        Metrics.globalRegistry.add(logboek)

        val result = try {
            service.run()
        } finally {
            Metrics.globalRegistry.remove(logboek)
        }

        assertEquals(1, result.totaalVerwijderd)
        assertEquals(1, result.ldvFouten)
        // Ook als afwijking van het logboek: het bericht is weg, zonder logregel.
        assertEquals(1.0, logboek.get("fbs.logboek.waarschuwing").tag("code", HardDeleteService.LDV_ALERT_TOKEN).counter().count())
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

    @Test
    fun `een ronde zonder fouten zet beide tijdstippen en telt de gewiste berichten`() {
        every { ops.deleteOne(any()) } returns 1
        every { ops.claim(any(), any(), any()) } returnsMany listOf(listOf(kandidaat(1L), kandidaat(2L)), emptyList())
        every { ops.telWachtend(any(), any()) } returns 0

        service.run()

        assertEquals(2.0, teller("fbs.retentie.verwijderd"))
        assertTrue(gauge("fbs.retentie.laatste.ronde") > 0)
        assertEquals(gauge("fbs.retentie.laatste.ronde"), gauge("fbs.retentie.laatste.geslaagde.ronde"))
        assertEquals(0.0, gauge("fbs.retentie.wachtend"))
    }

    @Test
    fun `een ronde met een fout is geen geslaagde ronde`() {
        val a = kandidaat(1L)
        every { ops.deleteOne(a) } throws RuntimeException("simuleer FK-violation")
        every { ops.claim(any(), any(), any()) } returnsMany listOf(listOf(a), emptyList())

        service.run()

        assertEquals(1.0, teller("fbs.retentie.fouten", "soort", "wissen"))
        assertTrue(gauge("fbs.retentie.laatste.ronde") > 0)
        assertEquals(0.0, gauge("fbs.retentie.laatste.geslaagde.ronde"))
    }

    @Test
    fun `een mislukte logboekregistratie telt apart`() {
        val a = kandidaat(1L)
        every { ops.deleteOne(a) } returns 1
        every { ldv.logHardDelete(a) } throws RuntimeException("LDV down")
        every { ops.claim(any(), any(), any()) } returnsMany listOf(listOf(a), emptyList())

        service.run()

        assertEquals(1.0, teller("fbs.retentie.fouten", "soort", "logboek"))
        assertEquals(0.0, teller("fbs.retentie.fouten", "soort", "wissen"))
        assertEquals(0.0, gauge("fbs.retentie.laatste.geslaagde.ronde"))
    }

    @Test
    fun `de achterstand na de ronde komt in de meting, en een mislukte telling laat de vorige staan`() {
        every { ops.claim(any(), any(), any()) } returns emptyList()
        every { ops.telWachtend(any(), any()) } returns 5
        every { ops.oudsteAchterstand(any(), any(), any(), any()) } returns 3_600

        service.run()

        assertEquals(5.0, gauge("fbs.retentie.wachtend"))
        assertEquals(3_600.0, gauge("fbs.retentie.achterstand.oudste"))

        every { ops.telWachtend(any(), any()) } throws RuntimeException("database weg")
        every { ops.oudsteAchterstand(any(), any(), any(), any()) } throws RuntimeException("database weg")

        service.run()

        assertEquals(5.0, gauge("fbs.retentie.wachtend"))
        assertEquals(3_600.0, gauge("fbs.retentie.achterstand.oudste"))
    }

    // Na een herstart, vóór de eerste ronde: de achterstand komt uit de database, met de drempels
    // uit de configuratie.
    @Test
    fun `bij de start staat de achterstand al in de meting`() {
        every { ops.telWachtend(any(), any()) } returns 2
        every { ops.oudsteAchterstand(any(), any(), Period.ofYears(7), Period.ofYears(7)) } returns 86_400

        service.bijStart(StartupEvent())

        assertEquals(2.0, gauge("fbs.retentie.wachtend"))
        assertEquals(86_400.0, gauge("fbs.retentie.achterstand.oudste"))
        assertEquals(0.0, gauge("fbs.retentie.laatste.ronde"))
        verify(exactly = 0) { ops.claim(any(), any(), any()) }
    }

    @Test
    fun `een mislukte meting bij de start houdt de dienst niet tegen`() {
        every { ops.telWachtend(any(), any()) } throws RuntimeException("database nog niet bereikbaar")
        every { ops.oudsteAchterstand(any(), any(), any(), any()) } throws RuntimeException("database nog niet bereikbaar")

        service.bijStart(StartupEvent())

        assertEquals(0.0, gauge("fbs.retentie.achterstand.oudste"))
    }

    @Test
    fun `een ronde die halverwege afbreekt, houdt wat al gewist was in de meting`() {
        val a = kandidaat(1L)
        val b = kandidaat(2L)
        every { ops.deleteOne(any()) } returns 1
        every { ldv.logHardDelete(b) } throws RuntimeException("LDV down")
        every { ops.claim(any(), any(), any()) } returns listOf(a, b) andThenThrows IllegalStateException("database weg")

        assertThrows<IllegalStateException> { service.run() }

        assertEquals(2.0, teller("fbs.retentie.verwijderd"))
        assertEquals(1.0, teller("fbs.retentie.fouten", "soort", "logboek"))
        assertEquals(0.0, gauge("fbs.retentie.laatste.ronde"))
    }
}
