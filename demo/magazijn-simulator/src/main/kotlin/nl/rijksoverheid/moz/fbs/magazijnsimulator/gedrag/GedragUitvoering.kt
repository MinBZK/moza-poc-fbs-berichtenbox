package nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag

import jakarta.enterprise.context.ApplicationScoped
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln

/**
 * Rekent uit wat een magazijn deze keer doet: hoe lang het erover doet, en of het omvalt.
 *
 * **Herhaalbaar, niet voorspelbaar-saai.** Elk magazijn heeft zijn eigen toevalsgenerator met een
 * vaste startwaarde uit zijn OIN. Daardoor levert een demo die je vandaag oefent morgen dezelfde
 * reeks op — een haperend magazijn valt op dezelfde momenten om — terwijl het binnen één ronde nog
 * steeds afwisselt. Zou de startwaarde per opstart geloot worden, dan is een demo niet te repeteren
 * en een bevinding niet na te spelen.
 */
@ApplicationScoped
class GedragUitvoering {

    private val generatoren = ConcurrentHashMap<String, Random>()

    /**
     * De vertraging voor deze aanroep in milliseconden.
     *
     * Bij [GedragModus.TRAAG] en [GedragModus.UIT] log-normaal verdeeld tussen de mediaan en het 95e
     * percentiel: de meeste antwoorden rond de mediaan, af en toe een forse uitschieter. Dat is hoe
     * echte responstijden eruitzien, en juist die staart bepaalt wanneer een ondernemer zijn lijst
     * compleet ziet. Een vaste vertraging zou dat effect wegpoetsen.
     */
    fun vertragingMs(oin: String, gedrag: Gedrag): Long {
        if (gedrag.latencyP95Ms == gedrag.latencyP50Ms) return gedrag.latencyP50Ms.toLong()

        val mediaan = maxOf(gedrag.latencyP50Ms, 1).toDouble()
        val p95 = maxOf(gedrag.latencyP95Ms, 1).toDouble()

        // Van twee percentielen naar de parameters van de verdeling: de mediaan ís exp(mu), en het
        // 95e percentiel ligt 1,645 standaardafwijkingen hoger op de logschaal.
        val mu = ln(mediaan)
        val sigma = (ln(p95) - mu) / Z_SCORE_P95
        val getrokken = exp(mu + sigma * generatorVoor(oin).nextGaussian())

        // Aan de bovenkant afkappen: de staart van een log-normale verdeling is onbegrensd, en één
        // uitschieter van een minuut is geen demonstratie maar een vastloper.
        return getrokken.coerceIn(0.0, p95 * MAX_UITSCHIETER_FACTOR).toLong()
    }

    /** Of deze aanroep omvalt. Alleen [GedragModus.HAPERT] wisselt; de rest is beslist door de modus. */
    fun valtOm(oin: String, gedrag: Gedrag): Boolean = when (gedrag.modus) {
        GedragModus.STUK, GedragModus.WEIGERT -> true
        GedragModus.HAPERT -> generatorVoor(oin).nextDouble() < gedrag.foutkans
        else -> false
    }

    private fun generatorVoor(oin: String): Random =
        generatoren.computeIfAbsent(oin) { Random(it.hashCode().toLong()) }

    private companion object {
        /** Het 95e percentiel van een standaardnormale verdeling. */
        const val Z_SCORE_P95 = 1.645

        /** Hoever een uitschieter boven het 95e percentiel mag komen. */
        const val MAX_UITSCHIETER_FACTOR = 3.0
    }
}
