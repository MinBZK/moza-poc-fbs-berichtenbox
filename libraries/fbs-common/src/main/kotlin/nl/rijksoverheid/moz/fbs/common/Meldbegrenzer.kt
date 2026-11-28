package nl.rijksoverheid.moz.fbs.common

import java.util.concurrent.atomic.AtomicLong

/**
 * Hooguit één logregel per [intervalNanos] voor een gebeurtenis die vaak kan voorkomen, met het
 * aantal sinds de vorige regel. Een scraper met een verkeerd token komt elke halve minuut, en wie
 * een poort afzoekt, bepaalt anders zelf hoeveel log er ontstaat.
 *
 * Gebeurtenissen binnen het interval na een regel tellen mee in de volgende regel, en die komt pas
 * bij de eerste gebeurtenis na dat interval. Houdt een reeks daarvoor op, dan blijft hij tot dan
 * onzichtbaar: er is bewust geen timer, de begrenzer telt en plant niets in.
 *
 * Draadveilig zonder lock: elk gemeld aantal is groter dan 0, en samen met wat in de teller staat
 * is het precies het aantal gebeurtenissen.
 */
class Meldbegrenzer(private val intervalNanos: Long, private val klok: () -> Long = System::nanoTime) {

    private val vorigeMelding = AtomicLong(GEEN)
    private val sindsMelding = AtomicLong()

    /** Telt een gebeurtenis; geeft het te melden aantal terug als er nu een regel hoort, anders `null`. */
    fun gebeurtenis(): Long? {
        sindsMelding.incrementAndGet()

        val nu = klok()
        val vorige = vorigeMelding.get()

        if ((vorige != GEEN && nu - vorige < intervalNanos) || !vorigeMelding.compareAndSet(vorige, nu)) return null

        // In één keer leeghalen: twee winnaars kort na elkaar zouden met een eigen aftrek samen meer
        // melden dan er gebeurde. Wie na de eerste komt, vindt een lege teller en meldt niets.
        return sindsMelding.getAndSet(0).takeIf { it > 0 }
    }

    private companion object {
        private const val GEEN = Long.MIN_VALUE
    }
}
