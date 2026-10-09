package nl.rijksoverheid.moz.fbs.common

import java.sql.SQLException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Een fout zoals hij de applicatielog in mag: de types en de stack van de hele keten, zonder
 * messages.
 *
 * Een logger die een exceptie meekrijgt, drukt met de stacktrace ook de message af, en die
 * van elke cause en suppressed fout. Die messages zijn niet te vertrouwen: een database-driver
 * zet er het volledige `INSERT`-statement in, een HTTP-client een fragment van het antwoord.
 * Saneren met [FoutBeschrijving.saneer] volstaat niet, want dat laat vrije tekst ongemoeid.
 *
 * De stack blijft behouden, als throwable: log-pipelines die op het `thrown`-veld filteren
 * blijven werken. Van een [SQLException] gaat de SQLState mee; dat is een vaste code en
 * vaak het enige dat de oorzaak nog onderscheidt.
 */
class Foutspoor private constructor(beschrijving: String, oorzaak: Foutspoor?) :
    RuntimeException(beschrijving, oorzaak, true, true) {

    companion object {
        /** Begrenst de kopie; een cyclische keten is zeldzaam maar mogelijk. */
        const val MAX_FOUTEN = 32

        private const val SQLSTATE_LENGTE = 5

        fun van(fout: Throwable): Foutspoor {
            // Op identiteit: equals van een vreemde exceptieklasse zegt hier niets.
            val gezien = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())

            return kopieer(fout, gezien) ?: Foutspoor(beschrijving(fout), null)
        }

        /** `null` voor een fout die al in het spoor zit of voorbij de grens valt. */
        private fun kopieer(fout: Throwable, gezien: MutableSet<Throwable>): Foutspoor? {
            if (gezien.size >= MAX_FOUTEN || !gezien.add(fout)) return null

            // De cause gaat via de constructor: een Throwable die met een lege cause is
            // gemaakt, weigert daarna initCause.
            val spoor = Foutspoor(beschrijving(fout), fout.cause?.let { kopieer(it, gezien) })
            spoor.stackTrace = fout.stackTrace
            fout.suppressed.forEach { onderdrukt -> kopieer(onderdrukt, gezien)?.let(spoor::addSuppressed) }

            return spoor
        }

        private fun beschrijving(fout: Throwable): String {
            val type = fout.javaClass.name
            val sqlState = (fout as? SQLException)?.sqlState

            // Alleen een SQLState in de vaste vorm; de driver bepaalt wat erin staat.
            if (sqlState == null || sqlState.length != SQLSTATE_LENGTE || !sqlState.all(Char::isLetterOrDigit)) {
                return type
            }

            return "$type (SQLState=$sqlState)"
        }
    }
}
