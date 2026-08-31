package nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag

/**
 * Hoe een gesimuleerd magazijn zich gedraagt.
 *
 * De laatste twee zijn er niet voor de sier. De Berichtenbox behandelt een *beschikbaarheids*-
 * storing (timeout, 5xx, netwerk — die telt mee voor de circuit breaker) anders dan een magazijn dat
 * wél antwoordde maar iets onbruikbaars zei (4xx, of een body die niet te lezen is — die telt níét
 * mee). Met alleen de eerste vijf wordt die tweede tak in een demo nooit geraakt, terwijl juist die
 * eerder een echte fout opleverde: stub-antwoorden zonder `bijlagen`-veld die als onbruikbaar
 * binnenkwamen.
 */
enum class GedragModus {
    /** Antwoordt vlot en correct. */
    NORMAAL,

    /** Antwoordt correct, maar traag — met een lange staart, zoals echte responstijden. */
    TRAAG,

    /** Antwoordt meestal, maar valt met een zekere kans om. */
    HAPERT,

    /** Antwoordt consequent met een serverfout. */
    STUK,

    /** Reageert niet binnen de tijd die de uitvraag hem gunt. */
    UIT,

    /** Antwoordt netjes met een weigering: een 4xx in `problem+json`. */
    WEIGERT,

    /** Antwoordt met 200 en een body die niet aan het schema voldoet. */
    MALFORMED,
}

/**
 * Het volledige gedrag van één magazijn: de modus plus de getallen die erbij horen.
 *
 * Ze staan bij elkaar omdat ze alleen samen betekenis hebben — een `foutkans` zonder [GedragModus.HAPERT]
 * doet niets, en een `latencyP95Ms` onder de mediaan is geen spreiding maar een fout.
 */
data class Gedrag(
    val modus: GedragModus,
    val latencyP50Ms: Int = NORMALE_LATENCY_MS,
    val latencyP95Ms: Int = NORMALE_LATENCY_MS,
    val foutkans: Double = 0.0,
    val foutStatus: Int = STANDAARD_FOUT_STATUS,
) {
    init {
        require(latencyP50Ms >= 0) { "latencyP50Ms mag niet negatief zijn (kreeg $latencyP50Ms)" }
        require(latencyP95Ms >= latencyP50Ms) {
            "latencyP95Ms ($latencyP95Ms) hoort niet onder latencyP50Ms ($latencyP50Ms) te liggen"
        }
        require(foutkans in 0.0..1.0) { "foutkans hoort tussen 0 en 1 te liggen (kreeg $foutkans)" }
        require(foutStatus in FOUT_STATUS_BEREIK) { "foutStatus hoort een HTTP-foutcode te zijn (kreeg $foutStatus)" }
    }

    companion object {
        /** Wat een gezond magazijn kost: genoeg om zichtbaar te zijn, te weinig om op te vallen. */
        const val NORMALE_LATENCY_MS = 50

        const val STANDAARD_FOUT_STATUS = 503

        /** Een magazijn dat weigert antwoordt met een 4xx; 403 is de vorm die daar het beste bij past. */
        const val WEIGER_STATUS = 403

        private val FOUT_STATUS_BEREIK = 400..599

        val NORMAAL = Gedrag(GedragModus.NORMAAL)

        /**
         * Standaardwaardes per modus, gebruikt wanneer de configuratie alleen een modus noemt.
         *
         * De trage variant staat op een mediaan van 1,2 s met een 95e percentiel van 4 s: traag
         * genoeg om in een demo te zien dat je erop wacht, en niet zo traag dat hij in een timeout
         * loopt — dat is wat [GedragModus.UIT] doet.
         */
        fun standaardVoor(modus: GedragModus): Gedrag = when (modus) {
            GedragModus.NORMAAL -> NORMAAL
            GedragModus.TRAAG -> Gedrag(modus, latencyP50Ms = 1_200, latencyP95Ms = 4_000)
            GedragModus.HAPERT -> Gedrag(modus, foutkans = 0.5)
            GedragModus.STUK -> Gedrag(modus)
            // Ruim boven de query-timeout van tien seconden die de uitvraag per magazijn hanteert:
            // de aanroeper hoort af te breken, niet dit magazijn.
            GedragModus.UIT -> Gedrag(modus, latencyP50Ms = 15_000, latencyP95Ms = 15_000)
            GedragModus.WEIGERT -> Gedrag(modus, foutStatus = WEIGER_STATUS)
            GedragModus.MALFORMED -> Gedrag(modus)
        }
    }
}
