package nl.rijksoverheid.moz.fbs.common

import java.util.concurrent.atomic.AtomicLong

/**
 * Hooguit één logregel per [intervalNanos] voor een gebeurtenis die vaak kan voorkomen, met het
 * aantal sinds de vorige regel. Een scraper met een verkeerd token komt elke halve minuut, en wie
 * een poort afzoekt, bepaalt anders zelf hoeveel log er ontstaat.
 *
 * Een reeks die binnen het interval ophoudt, komt pas in de log bij de eerstvolgende gebeurtenis
 * na het interval. Er is bewust geen timer die hem later alsnog meldt: de eerste regel van de
 * reeks staat er al, en die is wat een beheerder zoekt.
 *
 * Draadveilig zonder lock: elke [gebeurtenis] wordt precies één keer meegeteld in een gemeld
 * aantal of in de rest van de teller.
 */
class Meldbegrenzer(private val intervalNanos: Long, private val klok: () -> Long = System::nanoTime) {

    private val vorigeMelding = AtomicLong(GEEN)
    private val sindsMelding = AtomicLong()

    /** Telt een gebeurtenis; geeft het te melden aantal terug als er nu een regel hoort, anders `null`. */
    fun gebeurtenis(): Long? {
        val aantal = sindsMelding.incrementAndGet()
        val nu = klok()
        val vorige = vorigeMelding.get()

        if ((vorige == GEEN || nu - vorige >= intervalNanos) && vorigeMelding.compareAndSet(vorige, nu)) {
            // Alleen wat deze aanroep zag; wat intussen binnenkwam, blijft staan voor de volgende.
            sindsMelding.addAndGet(-aantal)

            return aantal
        }

        return null
    }

    private companion object {
        private const val GEEN = Long.MIN_VALUE
    }
}
