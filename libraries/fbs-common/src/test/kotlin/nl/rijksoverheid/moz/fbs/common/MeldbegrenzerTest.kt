package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MeldbegrenzerTest {

    private val interval = 100L
    private var nu = 0L
    private val begrenzer = Meldbegrenzer(interval) { nu }

    @Test
    fun `de eerste gebeurtenis wordt meteen gemeld`() {
        assertEquals(1L, begrenzer.gebeurtenis())
    }

    @Test
    fun `binnen het interval geen regel, op de grens de opgespaarde mee`() {
        assertEquals(1L, begrenzer.gebeurtenis())

        nu = 1
        assertNull(begrenzer.gebeurtenis())

        nu = interval - 1
        assertNull(begrenzer.gebeurtenis())

        nu = interval
        assertEquals(3L, begrenzer.gebeurtenis())

        nu = interval + 1
        assertNull(begrenzer.gebeurtenis())

        nu = 2 * interval
        assertEquals(2L, begrenzer.gebeurtenis())
    }

    // Een klok die van negatief naar positief loopt (System.nanoTime mag elke waarde hebben).
    @Test
    fun `een negatieve klok werkt ook`() {
        nu = -50
        assertEquals(1L, begrenzer.gebeurtenis())

        nu = -50 + interval
        assertEquals(1L, begrenzer.gebeurtenis())
    }

    @Test
    fun `elke gebeurtenis telt precies een keer, ook bij gelijktijdige aanroepen`() {
        val klok = AtomicLong()
        val gedeeld = Meldbegrenzer(1) { klok.incrementAndGet() }
        val gemeld = AtomicLong()
        val draden = 8
        val perDraad = 5_000
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(draden)

        repeat(draden) {
            pool.submit {
                start.await()
                repeat(perDraad) { gedeeld.gebeurtenis()?.let(gemeld::addAndGet) }
            }
        }

        start.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        // Wat niet gemeld is, staat nog in de teller: de volgende melding neemt het mee.
        klok.addAndGet(10)
        val rest = (gedeeld.gebeurtenis() ?: 0L) - 1

        assertEquals((draden * perDraad).toLong(), gemeld.get() + rest)
    }
}
