package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure tests op de begrensde aggregatie-pool: daemon-threads + naamgeving, fail-fast bij
 * niet-positieve config, en de kern-eigenschap van de begrenzing — een volle pool+queue wijst
 * nieuwe taken af (i.p.v. onbegrensd te bufferen). De afwijzing is deterministisch met latches,
 * geen `Thread.sleep`.
 */
class MagazijnAggregatieExecutorTest {

    @Test
    fun `threads zijn daemon met de fbs-magazijn-aggregatie naam-prefix`() {
        val executor = MagazijnAggregatieExecutor(poolSize = 2, queueCapaciteit = 10)

        try {
            val naam = AtomicReference<String>()
            val daemon = AtomicBoolean(false)
            val klaar = CountDownLatch(1)

            executor.executor().execute {
                naam.set(Thread.currentThread().name)
                daemon.set(Thread.currentThread().isDaemon)
                klaar.countDown()
            }

            assertTrue(klaar.await(2, TimeUnit.SECONDS), "taak moet binnen 2s draaien")
            assertTrue(naam.get().startsWith("fbs-magazijn-aggregatie-"), "thread-naam was ${naam.get()}")
            assertTrue(daemon.get(), "aggregatie-threads moeten daemon zijn")
        } finally {
            executor.stop()
        }
    }

    @Test
    fun `volle pool en queue wijst nieuwe taken af`() {
        val executor = MagazijnAggregatieExecutor(poolSize = 1, queueCapaciteit = 1)
        val blokkeer = CountDownLatch(1)

        try {
            // Bezet de ene pool-thread én het ene queue-slot met blokkerende taken.
            executor.executor().execute { blokkeer.await() }
            executor.executor().execute { blokkeer.await() }

            // Derde taak: pool op max + queue vol → AbortPolicy wijst af.
            assertThrows(RejectedExecutionException::class.java) {
                executor.executor().execute { }
            }
        } finally {
            blokkeer.countDown()
            executor.stop()
        }
    }

    @Test
    fun `niet-positieve pool-size faalt fail-fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            MagazijnAggregatieExecutor(poolSize = 0, queueCapaciteit = 10)
        }
    }

    @Test
    fun `niet-positieve queue-capaciteit faalt fail-fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            MagazijnAggregatieExecutor(poolSize = 1, queueCapaciteit = 0)
        }
    }
}
