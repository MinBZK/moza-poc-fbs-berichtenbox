package nl.rijksoverheid.moz.fbs.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
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

    // Met een interval van 1 en een klok die bij elke aflezing doorloopt, winnen veel aanroepen de CAS
    // kort na elkaar: precies het geval waarin een eigen aftrek per winnaar dubbel zou melden.
    @Test
    fun `gelijktijdige aanroepen melden nooit meer dan er gebeurde`() {
        val klok = AtomicLong()
        val gedeeld = Meldbegrenzer(1) { klok.incrementAndGet() }
        val meldingen = ConcurrentLinkedQueue<Long>()
        val draden = 8
        val perDraad = 5_000
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(draden)

        val taken = (1..draden).map {
            pool.submit {
                start.await()
                repeat(perDraad) { gedeeld.gebeurtenis()?.let(meldingen::add) }
            }
        }

        start.countDown()
        taken.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))

        // Wat niet gemeld is, staat nog in de teller: de volgende melding neemt het mee, plus zichzelf.
        klok.addAndGet(10)
        val rest = (gedeeld.gebeurtenis() ?: 0L) - 1
        val totaal = (draden * perDraad).toLong()

        assertTrue(meldingen.all { it > 0 }, "een melding van 0 of minder")
        assertTrue(rest >= 0, "de teller is onder 0 gezakt")
        assertEquals(totaal, meldingen.sum() + rest)
    }
}
