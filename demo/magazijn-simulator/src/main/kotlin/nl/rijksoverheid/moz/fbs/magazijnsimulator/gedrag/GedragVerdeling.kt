package nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag

/**
 * Welk gedrag hoort bij welk gesimuleerd magazijn.
 *
 * De verdeling is deterministisch uit het volgnummer, zonder seed: elke omgeving — een laptop, de
 * gedeelde omgeving, een preview — krijgt dezelfde verdeling, en een demo die je vandaag oefent
 * gedraagt zich morgen hetzelfde. Zou dit per opstart geloot worden, dan is een demo niet te
 * repeteren en is een bevinding niet na te spelen.
 *
 * De verdeling is realistisch bedoeld: veruit de meeste organisaties doen het gewoon, een handvol is
 * traag, een paar liggen eruit. Over de achtennegentig gesimuleerde magazijnen komt dat neer op
 * 72 normaal, 15 traag, 4 haperend, 3 stuk, 2 onbereikbaar, 1 weigerend en 1 onbruikbaar.
 *
 * De plaatsing van die laatste twee is niet willekeurig: index 22 valt binnen de persona met 45
 * organisaties en index 71 alleen binnen de grootste. Zo houden de twee kleinste persona's een schoon
 * contrast — daar valt niets uit — en wordt de tak "wel bereikbaar, maar geen bruikbaar antwoord"
 * pas zichtbaar in de grote scenario's, waar hij thuishoort.
 */
object GedragVerdeling {

    /** Volgnummers die helemaal niet reageren. */
    private val UIT = setOf(28, 97)

    /** Volgnummers die consequent een serverfout geven. */
    private val STUK = setOf(33, 66, 98)

    private const val WEIGERT_INDEX = 22
    private const val MALFORMED_INDEX = 71
    private const val HAPERT_ELKE = 20
    private const val TRAAG_ELKE = 5

    /**
     * Het gedrag bij een volgnummer, in volgorde van voorrang. De volgorde telt: index 20 is zowel
     * een veelvoud van 20 als van 5, en hoort te haperen en niet traag te zijn.
     */
    fun voorIndex(index: Int): Gedrag {
        // Volgnummers beginnen bij één. Zonder deze controle zou nul als veelvoud van twintig als
        // haperend magazijn eindigen, en dat is geen keuze maar een rekenfout.
        require(index >= 1) { "Volgnummer van een gesimuleerd magazijn begint bij 1 (kreeg $index)" }

        val modus = when {
            index in UIT -> GedragModus.UIT
            index in STUK -> GedragModus.STUK
            index == WEIGERT_INDEX -> GedragModus.WEIGERT
            index == MALFORMED_INDEX -> GedragModus.MALFORMED
            index % HAPERT_ELKE == 0 -> GedragModus.HAPERT
            index % TRAAG_ELKE == 0 -> GedragModus.TRAAG
            else -> GedragModus.NORMAAL
        }

        return Gedrag.standaardVoor(modus)
    }
}
