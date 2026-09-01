package nl.rijksoverheid.moz.fbs.magazijnsimulator.pool

import java.time.Duration

/**
 * Wat de connection pool op één moment doet.
 *
 * Losse waarden en geen Agroal-typen: zo is de opmaak en het oordeel "is er iets veranderd" te
 * toetsen zonder database, en dat is precies het deel dat stil kan rotten.
 */
data class Poolmoment(
    val inGebruik: Long,
    val vrij: Long,
    val wachtend: Long,
    val max: Int,
    val piek: Long,
    val opgezet: Long,
    val vernietigd: Long,
    val wachtenGemiddeld: Duration,
    val wachtenLangst: Duration,
    val wachtenTotaal: Duration,
) {

    /**
     * Alleen loggen wanneer er iets bewoog. Onder last verandert er elke tick iets en krijg je het
     * verloop; zodra het stil is houdt de regel op. Anders loopt een demo van een half uur vol met
     * identieke regels en valt het moment dat ertoe doet niet meer op.
     *
     * `max` telt niet mee: dat is een instelling, geen meting.
     */
    fun verschiltVan(vorige: Poolmoment?): Boolean = vorige == null || copy(max = 0) != vorige.copy(max = 0)

    /**
     * De regel zoals hij in de log komt. Drie groepen, in de volgorde waarin je ze leest: wat er nú
     * omgaat, wat de pool ooit aan de database heeft gevraagd, en wat het wachten kostte.
     */
    fun regel(): String =
        "pool: $inGebruik in gebruik, $vrij vrij, $wachtend wachtend van max $max | piek $piek | " +
            "opgezet $opgezet, vernietigd $vernietigd | " +
            "wachten gem ${kort(wachtenGemiddeld)}, langst ${kort(wachtenLangst)}, " +
            "totaal ${kort(wachtenTotaal)}"

    // Milliseconden tot een seconde, daarna seconden met één decimaal: een demo leest mee terwijl
    // het gebeurt, en "PT12.4S" of "412183000ns" kost dan een denkstap te veel.
    private fun kort(duur: Duration): String {
        val millis = duur.toMillis()

        return if (millis < MILLIS_PER_SECONDE) "${millis}ms" else "%.1fs".format(millis / MILLIS_PER_SECONDE.toDouble())
    }

    private companion object {

        const val MILLIS_PER_SECONDE = 1000L
    }
}
