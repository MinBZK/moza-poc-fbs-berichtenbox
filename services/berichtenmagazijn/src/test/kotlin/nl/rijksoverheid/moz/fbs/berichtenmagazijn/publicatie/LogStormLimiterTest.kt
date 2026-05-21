package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Borgt het atomicity-contract van [LogStormLimiter]:
 *
 *  - Eerste call → `true`, opvolgende binnen cooldown → `false`.
 *  - Na cooldown verstrijken → opnieuw `true`.
 *  - 2-thread race op de eerste call → exact ÉÉN `true` (niet beide).
 *    Een refactor naar `if-absent + put` zou functioneel slagen op
 *    sequential tests maar deze atomicity breken — vandaar de race-test.
 *  - Negatieve/nul cooldown wordt fail-fast geweigerd.
 */
class LogStormLimiterTest {

    private class MutableClock(start: Instant) : Clock() {
        var now: Instant = start
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
    }

    @Test
    fun `eerste emit slaagt, tweede binnen cooldown wordt geweigerd`() {
        val clock = MutableClock(Instant.parse("2026-05-12T10:00:00Z"))
        val limiter = LogStormLimiter<String>(Duration.ofMinutes(5), clock)

        assertTrue(limiter.magEmitten("doel-a"))
        assertFalse(limiter.magEmitten("doel-a"))
    }

    @Test
    fun `verschillende sleutels delen geen cooldown`() {
        val clock = MutableClock(Instant.parse("2026-05-12T10:00:00Z"))
        val limiter = LogStormLimiter<String>(Duration.ofMinutes(5), clock)

        assertTrue(limiter.magEmitten("doel-a"))
        assertTrue(limiter.magEmitten("doel-b"))
    }

    @Test
    fun `na cooldown verstrijken mag opnieuw geemit worden`() {
        val clock = MutableClock(Instant.parse("2026-05-12T10:00:00Z"))
        val cooldown = Duration.ofMinutes(5)
        val limiter = LogStormLimiter<String>(cooldown, clock)

        assertTrue(limiter.magEmitten("doel"))
        assertFalse(limiter.magEmitten("doel"))

        clock.now = clock.now.plus(cooldown).plusSeconds(1)
        assertTrue(limiter.magEmitten("doel"))
    }

    @Test
    fun `2-thread race op eerste call levert exact 1 true op (atomicity)`() {
        val clock = MutableClock(Instant.parse("2026-05-12T10:00:00Z"))
        val limiter = LogStormLimiter<String>(Duration.ofMinutes(5), clock)
        val pool = Executors.newFixedThreadPool(2)
        try {
            // Herhaal iteraties met verse sleutels — vergroot kans dat een
            // non-atomair `if-absent + put` patroon detectable wordt. 100 is
            // empirisch genoeg om de race te detecteren (als hij bestaat) zonder
            // test-tijd onnodig op te blazen.
            val races = 100
            var doubleEmits = 0
            repeat(races) { i ->
                val sleutel = "race-$i"
                val emitCount = AtomicInteger(0)
                val start = CountDownLatch(1)
                val done = CountDownLatch(2)
                repeat(2) {
                    pool.submit {
                        start.await()
                        if (limiter.magEmitten(sleutel)) emitCount.incrementAndGet()
                        done.countDown()
                    }
                }
                start.countDown()
                assertTrue(
                    done.await(2, TimeUnit.SECONDS),
                    "race-iteratie $i hung — pool stalled, geen valide observatie",
                )
                if (emitCount.get() != 1) doubleEmits++
            }
            assertEquals(0, doubleEmits, "race detecteerd: $doubleEmits van $races iteraties hadden ≠1 emit")
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `negatieve cooldown wordt fail-fast geweigerd`() {
        val clock = Clock.systemUTC()
        assertThrows(IllegalArgumentException::class.java) {
            LogStormLimiter<String>(Duration.ofSeconds(-1), clock)
        }
    }

    @Test
    fun `nul cooldown wordt fail-fast geweigerd`() {
        val clock = Clock.systemUTC()
        assertThrows(IllegalArgumentException::class.java) {
            LogStormLimiter<String>(Duration.ZERO, clock)
        }
    }
}
