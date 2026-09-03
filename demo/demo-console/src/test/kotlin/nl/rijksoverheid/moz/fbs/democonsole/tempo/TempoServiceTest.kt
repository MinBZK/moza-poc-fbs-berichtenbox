package nl.rijksoverheid.moz.fbs.democonsole.tempo

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.BadRequestException
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.generator.DemoBerichtGenerator
import nl.rijksoverheid.moz.fbs.democonsole.generator.Organisatie
import nl.rijksoverheid.moz.fbs.democonsole.generator.Persona
import nl.rijksoverheid.moz.fbs.democonsole.generator.Sjabloon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Verzette klok: de duurgrens is anders alleen te toetsen door een uur te wachten. */
private class TestKlok(var nu: Instant = Instant.parse("2026-08-26T10:00:00Z")) : Clock() {

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = nu
}

/** Voert de geplande taak niet zelf uit; de test tikt met de hand, zodat er niets te wachten valt. */
private class HandKlok : TempoKlok {

    var taak: (() -> Unit)? = null
    var gestopt = 0

    override fun start(intervalSeconden: Int, tik: () -> Unit) {
        taak = tik
    }

    override fun stop() {
        taak = null
        gestopt++
    }

    fun tik(keer: Int = 1) = repeat(keer) { taak?.invoke() }
}

class TempoServiceTest {

    private val klok = HandKlok()
    private val testKlok = TestKlok()
    private val aanleverService = mockk<AanleverService>()

    // Geen mockk<DemoBerichtGenerator>(): de klasse is niet @ApplicationScoped en dus finaal,
    // en MockK kan finale klassen alleen via zijn inline-agent aan, die deze module niet gebruikt.
    private val rvo = "00000000000000100000"

    private val organisaties = mapOf(
        rvo to Organisatie(rvo, "RVO", listOf(Sjabloon("Subsidie", "Uw subsidie is toegekend."))),
    )

    private val personas = listOf(Persona("pietersen", "J. Pietersen", "BSN", "999993653", listOf(rvo)))

    private val generator = DemoBerichtGenerator(
        personas,
        organisaties,
        Clock.fixed(Instant.parse("2026-07-01T12:00:00Z"), ZoneOffset.UTC),
    )

    private val service = TempoService(klok, aanleverService, generator, testKlok)

    init {
        every { aanleverService.leverAan(any()) } returns AanleverResultaat(1, 1, 0, 0)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1, 3601, 86400])
    fun `een interval buiten de grenzen wordt geweigerd`(interval: Int) {
        // BadRequestException en geen require(): DemoFoutMapper maakt van een gewone
        // IllegalArgumentException een 500, en een bedieningsfout hoort een 400 te zijn.
        assertThrows(BadRequestException::class.java) { service.start(interval) }

        assertFalse(service.status().loopt)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 5, 3600])
    fun `een interval binnen de grenzen start de stroom`(interval: Int) {
        val status = service.start(interval)

        assertTrue(status.loopt)
        assertEquals(interval, status.intervalSeconden)
    }

    @Test
    fun `elke tik levert een bericht aan`() {
        service.start(5)

        klok.tik(3)

        verify(exactly = 3) { aanleverService.leverAan(any()) }
        assertEquals(3, service.status().geleverd)
    }

    @Test
    fun `een tweede start vervangt de lopende stroom in plaats van te stapelen`() {
        service.start(5)
        service.start(10)

        klok.tik()

        assertEquals(10, service.status().intervalSeconden)
        assertEquals(1, service.status().geleverd)
        verify(exactly = 1) { aanleverService.leverAan(any()) }
    }

    @Test
    fun `stop zonder lopende stroom is geen fout`() {
        val status = service.stop()

        assertFalse(status.loopt)
    }

    @Test
    fun `een tik die al onderweg was toen stop draaide, levert niets meer af`() {
        // Race die HandKlok.tik() niet dekt: die belt via zijn eigen taak-veld, dat stop() al op
        // null zet, dus een tik ná stop() bereikt tik() daar nooit. Hier houden we de taak apart
        // vast — zoals een scheduler-thread die de tik al gepakt had vlak vóór stop() gestartOp
        // op null zette — en roepen 'm daarna alsnog aan. De guard `gestartOp ?: return` moet die
        // uitgedeelde tik laten verlopen, anders landt er een bericht ná de herstelknop.
        service.start(5)

        val inFlightTik = klok.taak!!

        service.stop()
        inFlightTik.invoke()

        assertFalse(service.status().loopt)
        assertEquals(0, service.status().geleverd)
        verify(exactly = 0) { aanleverService.leverAan(any()) }
    }

    @Test
    fun `de stroom stopt vanzelf bij het maximum aantal berichten`() {
        // Op een gedeelde omgeving klikt iemand de SSO-sessie weg terwijl de stroom doorloopt.
        service.start(1)

        klok.tik(TempoService.MAX_BERICHTEN + 1)

        assertFalse(service.status().loopt)
        assertEquals(TempoService.MAX_BERICHTEN, service.status().geleverd)
    }

    @Test
    fun `de stroom stopt vanzelf na de maximale duur`() {
        service.start(1)
        klok.tik()

        testKlok.nu = testKlok.nu.plus(TempoService.MAX_DUUR).plus(Duration.ofSeconds(1))
        klok.tik()

        assertFalse(service.status().loopt)
        assertEquals(1, service.status().geleverd)
    }
}
