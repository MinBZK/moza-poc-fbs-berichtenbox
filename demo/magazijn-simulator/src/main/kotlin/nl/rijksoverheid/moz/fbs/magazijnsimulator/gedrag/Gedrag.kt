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
 *
 * Dat "alleen samen betekenis" is een invariant en geen aanbeveling, dus dwingt dit type het af.
 * Een combinatie die zichzelf tegenspreekt is in een demo erger dan een fout: een magazijn met
 * modus [GedragModus.HAPERT] en foutkans nul staat als haperend in het overzicht en hapert nooit,
 * en een [GedragModus.WEIGERT] met een 5xx telt bij de Berichtenbox juist als beschikbaarheids-
 * storing — het omgekeerde van wat die modus laat zien. Wie meekijkt heeft dan geen reden om de
 * knop te wantrouwen in plaats van het stelsel.
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

        val bezwaar = bezwaarTegenModus(modus, latencyP50Ms, latencyP95Ms, foutkans, foutStatus)

        require(bezwaar == null) { bezwaar.orEmpty() }
    }

    companion object {
        /** Wat een gezond magazijn kost: genoeg om zichtbaar te zijn, te weinig om op te vallen. */
        const val NORMALE_LATENCY_MS = 50

        const val STANDAARD_FOUT_STATUS = 503

        /** Een magazijn dat weigert antwoordt met een 4xx; 403 is de vorm die daar het beste bij past. */
        const val WEIGER_STATUS = 403

        /**
         * De bovengrens op de vertraging van een traag magazijn, ruim onder de tien seconden die de
         * uitvraag een magazijn gunt. `GedragUitvoering` kapt elke trekking hierop af; hier wordt een
         * ingestelde waarde erbóven geweigerd, want anders toont het overzicht een getal dat het
         * magazijn nooit haalt en zegt de presentator twintig seconden waar er acht komen.
         */
        const val TRAAG_PLAFOND_MS = 8_000

        private val FOUT_STATUS_BEREIK = 400..599

        private val CLIENT_FOUT_BEREIK = 400..499

        private val SERVER_FOUT_BEREIK = 500..599

        /**
         * Wat er niet klopt aan deze combinatie van modus en getallen, of `null` als ze bij elkaar
         * passen.
         *
         * Als aparte functie omdat twee wegen hem nodig hebben met een verschillende uitkomst: hier
         * in `init` als `require` (een programmeerfout, 500), en in het beheerpad als domeinfout op
         * invoer uit een JSON-body (400 met een melding die zegt wat er mis is).
         *
         * Elke modus die het overzicht toont, wordt getoetst. Een combinatie die het overzicht
         * tegenspreekt is in een demo erger dan een fout: wie meekijkt wantrouwt dan het stelsel in
         * plaats van de knop.
         */
        fun bezwaarTegenModus(
            modus: GedragModus,
            latencyP50Ms: Int,
            latencyP95Ms: Int,
            foutkans: Double,
            foutStatus: Int,
        ): String? = when (modus) {
            GedragModus.NORMAAL -> (
                "een gezond magazijn antwoordt binnen $NORMALE_LATENCY_MS ms; voor trager gedrag is " +
                    "er TRAAG (kreeg latencyP50Ms $latencyP50Ms)"
                ).takeIf { latencyP50Ms > NORMALE_LATENCY_MS }

            GedragModus.TRAAG -> traagBezwaar(latencyP50Ms, latencyP95Ms)

            GedragModus.HAPERT ->
                "een haperend magazijn hoort een foutkans boven nul te hebben, anders hapert het nooit"
                    .takeIf { foutkans <= 0.0 }
                    ?: storingBezwaar(modus, foutStatus)

            GedragModus.STUK, GedragModus.UIT -> storingBezwaar(modus, foutStatus)

            GedragModus.WEIGERT -> (
                "een weigering is een clientfout; foutStatus hoort tussen ${CLIENT_FOUT_BEREIK.first} " +
                    "en ${CLIENT_FOUT_BEREIK.last} te liggen (kreeg $foutStatus)"
                ).takeIf { foutStatus !in CLIENT_FOUT_BEREIK }

            // MALFORMED hangt aan geen van deze getallen: die antwoordt altijd met 200 en een
            // onbruikbare body.
            GedragModus.MALFORMED -> null
        }

        /**
         * TRAAG moet trager zijn dan gezond én binnen wat de simulator werkelijk laat zien: elke
         * trekking wordt op [TRAAG_PLAFOND_MS] afgekapt, dus een hogere instelling zou een getal in
         * het overzicht zetten dat nooit voorkomt.
         */
        private fun traagBezwaar(latencyP50Ms: Int, latencyP95Ms: Int): String? = when {
            latencyP50Ms <= NORMALE_LATENCY_MS ->
                "een traag magazijn hoort trager te antwoorden dan een gezond magazijn " +
                    "($NORMALE_LATENCY_MS ms); kreeg latencyP50Ms $latencyP50Ms"

            latencyP95Ms > TRAAG_PLAFOND_MS ->
                "een traag magazijn antwoordt binnen $TRAAG_PLAFOND_MS ms, anders loopt de aanroeper " +
                    "in zijn timeout en is het UIT; kreeg latencyP95Ms $latencyP95Ms"

            else -> null
        }

        /**
         * STUK, UIT en HAPERT tonen zich in de Berichtenbox als beschikbaarheidsstoring — die telt
         * mee voor de circuit breaker. Met een 4xx erop zou het magazijn als contentfout binnenkomen
         * en dus het tegenovergestelde laten zien van wat het overzicht belooft; dat is precies het
         * onderscheid dat WEIGERT moet maken.
         */
        private fun storingBezwaar(modus: GedragModus, foutStatus: Int): String? = (
            "$modus is een beschikbaarheidsstoring; foutStatus hoort tussen ${SERVER_FOUT_BEREIK.first} " +
                "en ${SERVER_FOUT_BEREIK.last} te liggen (kreeg $foutStatus)"
            ).takeIf { foutStatus !in SERVER_FOUT_BEREIK }

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
