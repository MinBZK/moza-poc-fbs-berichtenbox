package nl.rijksoverheid.moz.fbs.common

import java.security.MessageDigest

/**
 * Wie de beheerpoort (`/q/metrics`) mag lezen: alleen wie het bearer-token meestuurt dat het
 * platform de dienst als `METRICS_AUTH_TOKEN` geeft. Op ZAD is dat de dienst `metrics-scraper`,
 * die hetzelfde token aan de Prometheus van het cluster geeft.
 *
 * De poort heeft geen route naar buiten, maar binnen het cluster is het pod-adres bereikbaar
 * voor alles wat het netwerkbeleid doorlaat. De meetwaarden bevatten geen persoonsgegevens,
 * wel de bedrijfsvoering: welke magazijnen er zijn, hoeveel berichten er wachten, waar het
 * logboek faalt. Dat hoort niet bij elke buur in het cluster.
 *
 * Framework-vrij en puur; het plaatsen op de router gebeurt per dienst
 * (`BeheerpoortBeveiliging`), want een HTTP-bedrading hier zou elke consumer van fbs-common
 * erven, ook die zonder HTTP-laag.
 */
object BeheerpoortToegang {

    /**
     * De profielen waarin de beheerpoort zonder token open mag staan, zodat een lokale
     * opstelling en de tests zonder geheim werken. Een exacte allowlist, net als bij de
     * transport-validators: een samengesteld profiel (`dev,demo`) valt erbuiten en blijft
     * dicht.
     */
    val PROFIELEN_ZONDER_TOKEN = setOf("dev", "test")

    /** Alert-token in de log als de poort buiten dev/test geen token heeft en dus alles weigert. */
    const val TOKEN_ONTBREEKT = "BEHEERPOORT_ZONDER_TOKEN"

    /** Alert-token in de log als een verzoek zonder geldig token kwam. */
    const val GEWEIGERD = "BEHEERPOORT_GEWEIGERD"

    private const val SCHEMA = "Bearer "

    /**
     * Mag dit verzoek door? [verwacht] is het geconfigureerde token, `null` of leeg als er geen
     * is; [authorization] de `Authorization`-header zoals hij binnenkwam.
     *
     * Zonder geconfigureerd token is de poort alleen in [PROFIELEN_ZONDER_TOKEN] open. Elders
     * is hij dan dicht in plaats van open: een vergeten geheim mag geen open endpoint opleveren.
     * Mét token geldt de eis in elk profiel, ook in dev en test.
     */
    fun toegestaan(verwacht: String?, authorization: String?, profiel: String): Boolean {
        if (verwacht.isNullOrBlank()) return profiel in PROFIELEN_ZONDER_TOKEN

        val aangeboden = bearer(authorization) ?: return false

        // Constante tijd: een vergelijking die bij het eerste verschil stopt, verraadt via de
        // looptijd hoeveel tekens van een gok kloppen.
        return MessageDigest.isEqual(aangeboden.toByteArray(), verwacht.toByteArray())
    }

    /**
     * Het token uit een `Authorization: Bearer <token>`-header. Het schema is hoofdletterongevoelig
     * (RFC 9110 11.1), het token niet. Eén spatie, geen extra witruimte: een header die daarvan
     * afwijkt, komt niet van de scraper.
     */
    private fun bearer(authorization: String?): String? {
        if (authorization == null || authorization.length <= SCHEMA.length) return null

        if (!authorization.regionMatches(0, SCHEMA, 0, SCHEMA.length, ignoreCase = true)) return null

        return authorization.substring(SCHEMA.length).takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
    }
}
