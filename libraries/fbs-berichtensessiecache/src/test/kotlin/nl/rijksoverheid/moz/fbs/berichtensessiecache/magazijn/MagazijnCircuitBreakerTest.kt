package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * State-machine van de per-magazijn circuit breaker, deterministisch gepind met een
 * injecteerbare klok (geen `Thread.sleep`). Plus de per-magazijn isolatie van de
 * CDI-wrapper.
 */
class MagazijnCircuitBreakerTest {

    private var nu = 0L
    private val klok = { nu }

    private fun breaker(drempel: Int = 3, openNanos: Long = 1_000L) = Breaker(drempel, openNanos, klok)

    @Test
    fun `gesloten circuit laat alles door`() {
        val b = breaker()

        repeat(5) { assertTrue(b.toegestaan()) }
    }

    @Test
    fun `opent na drempel opeenvolgende fouten`() {
        val b = breaker(drempel = 3)

        b.meldFout()
        b.meldFout()

        assertTrue(b.toegestaan(), "nog onder de drempel")

        b.meldFout()

        assertFalse(b.toegestaan(), "drempel bereikt → open")
    }

    @Test
    fun `open circuit slaat calls over tot het venster verstrijkt`() {
        val b = breaker(drempel = 1, openNanos = 1_000L)

        b.meldFout()

        assertFalse(b.toegestaan())

        nu += 999

        assertFalse(b.toegestaan(), "venster nog niet verstreken")

        nu += 1

        assertTrue(b.toegestaan(), "venster verstreken → half-open proef toegestaan")
    }

    @Test
    fun `na het venster is precies één half-open proef toegestaan`() {
        val b = breaker(drempel = 1, openNanos = 1_000L)

        b.meldFout()
        nu += 1_000

        assertTrue(b.toegestaan(), "eerste proef toegestaan")
        assertFalse(b.toegestaan(), "tweede gelijktijdige proef geweigerd")
    }

    @Test
    fun `half-open proef-succes sluit het circuit`() {
        val b = breaker(drempel = 1, openNanos = 1_000L)

        b.meldFout()
        nu += 1_000

        assertTrue(b.toegestaan())
        b.meldSucces()

        repeat(3) { assertTrue(b.toegestaan(), "circuit weer gesloten") }
    }

    @Test
    fun `half-open proef-fout heropent het circuit`() {
        val b = breaker(drempel = 1, openNanos = 1_000L)

        b.meldFout()
        nu += 1_000

        assertTrue(b.toegestaan())
        b.meldFout()

        assertFalse(b.toegestaan(), "proef faalde → opnieuw open")

        nu += 1_000

        assertTrue(b.toegestaan(), "nieuw venster verstreken → nieuwe proef")
    }

    @Test
    fun `verspreide fouten met tussentijds succes openen het circuit niet`() {
        val b = breaker(drempel = 3)

        b.meldFout()
        b.meldFout()
        b.meldSucces()
        b.meldFout()
        b.meldFout()

        assertTrue(b.toegestaan(), "succes resette de teller, drempel nooit bereikt")
    }

    @Test
    fun `wrapper isoleert per magazijn`() {
        val cb = MagazijnCircuitBreaker(drempel = 2, openSeconds = 30L)

        cb.meldFout("magazijn-a")
        cb.meldFout("magazijn-a")

        assertFalse(cb.toegestaan("magazijn-a"), "A open na 2 fouten")
        assertTrue(cb.toegestaan("magazijn-b"), "B ongemoeid")
    }
}
