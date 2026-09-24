package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.GedragModus
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Identificatie
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.IdentificatieType

/**
 * In welke map een demobericht staat. Alleen de persona die de mappen demonstreert krijgt mappen:
 * of vrije mappen er komen is nog niet besloten, dus de andere persona's blijven zonder, en een demo
 * over iets anders loopt er niet tegenaan.
 *
 * De map hangt aan het gedrag van het magazijn, zodat één ophaalronde alle gevolgen laat zien van
 * een map die bij het bericht hoort en niet los wordt bewaard:
 *
 * - [VERSPREID] staat bij elke organisatie die gewoon of traag antwoordt: het aantal loopt op terwijl
 *   de organisaties leveren.
 * - [ALLEEN_TRAAG] staat alleen bij trage organisaties: de map verschijnt pas na een paar seconden.
 * - [ALLEEN_ONBEREIKBAAR] staat alleen bij organisaties die niet leveren: de map verschijnt nooit, en
 *   de melding over wie niet leverde verklaart waarom.
 * - [EEN_BERICHT] staat bij precies één bericht: haal het eruit, en de map is weg.
 *
 * Deterministisch, zoals de rest van de demo: dezelfde omgeving levert dezelfde mappen.
 */
object DemoMappen {

    /** De persona uit `demo.personas.proeftuin-vier` en `demo/genereer-magazijnen.py`. */
    val ONTVANGER = Identificatie(IdentificatieType.KVK, "90000015")

    const val VERSPREID = "Vergunningen"
    const val ALLEEN_TRAAG = "Subsidies"
    const val ALLEEN_ONBEREIKBAAR = "Handhaving"
    const val EEN_BERICHT = "Te bespreken met adviseur"

    /**
     * Het eerste gesimuleerde magazijn uit het generatiescript. Een vast magazijn en geen "het eerste
     * dat gevuld wordt": vullen gaat per magazijn en kan halverwege worden hervat, en dan zou de map
     * met één bericht er twee krijgen.
     */
    const val EEN_BERICHT_MAGAZIJN = "00000009000000000001"

    private const val EERSTE = 1
    private const val TWEEDE = 2
    private const val DERDE = 3

    fun voor(ontvanger: Identificatie, magazijnOin: String, gedrag: GedragModus, volgnummer: Int): String? {
        if (ontvanger != ONTVANGER) return null

        return when {
            volgnummer == EERSTE && gedrag == GedragModus.TRAAG -> ALLEEN_TRAAG
            volgnummer == EERSTE && gedrag in ONBEREIKBAAR -> ALLEEN_ONBEREIKBAAR
            volgnummer == TWEEDE && gedrag in LEVEREND -> VERSPREID
            volgnummer == DERDE && magazijnOin == EEN_BERICHT_MAGAZIJN -> EEN_BERICHT
            else -> null
        }
    }

    /** Leveren altijd; haperend en weigerend doen dat niet betrouwbaar en blijven erbuiten. */
    private val LEVEREND = setOf(GedragModus.NORMAAL, GedragModus.TRAAG)

    private val ONBEREIKBAAR = setOf(GedragModus.UIT, GedragModus.STUK)
}
