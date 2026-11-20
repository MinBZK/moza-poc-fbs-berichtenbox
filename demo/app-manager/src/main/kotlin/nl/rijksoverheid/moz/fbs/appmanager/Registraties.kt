package nl.rijksoverheid.moz.fbs.appmanager

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.rechtstreeks.OpslagVolException
import org.jboss.logging.Logger
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Een gekozen persona, gebonden aan de sleutel van de app die erom vroeg. `toString` laat ontvanger
 * en client weg: die horen niet in een log.
 */
class Registratie(val ontvanger: String, val thumbprint: String, val client: String, val verlooptOp: Instant) {
    override fun toString(): String = "Registratie(thumbprint=$thumbprint, verlooptOp=$verlooptOp)"
}

/**
 * Wat de App Manager tussen de stappen van het activeren onthoudt, in het geheugen en begrensd:
 *
 * - een **koppeling**: het `session_token` uit de URL van `/register_app`, omgewisseld voor een
 *   cookie. Het token werkt daarvoor één keer, zodat een gelekte URL niets meer opent; de gebruikte
 *   tokens blijven daarom tot het einde van hun sessie bewaard;
 * - een **afgeronde koppeling**: de doorverwijzing na de keuze, korte tijd opnieuw op te vragen
 *   voor een dubbele submit;
 * - een **registratie**: het `registration_token` met de gekozen ontvanger en de thumbprint van de
 *   sleutel van de app. Met dat token haalt alleen die sleutel een (vers) credential op.
 *
 * Alles staat in het geheugen van één instantie: na een herstart, of met meer dan één replica,
 * activeert een app opnieuw.
 */
@ApplicationScoped
class Registraties(private val klok: Clock) {

    private class Koppeling(val sessieToken: String, val verlooptOp: Instant)

    private class Afgerond(val terug: String, val verlooptOp: Instant)

    private val willekeur = SecureRandom()

    // Alleen in het geheugen, nieuw bij elke start: zonder deze sleutel is een vingerafdruk in de log
    // niet terug te rekenen naar een adres, ook niet door alle IPv4-adressen te proberen.
    private val logsleutel = SecretKeySpec(ByteArray(LOGSLEUTEL_BYTES).also(willekeur::nextBytes), HMAC)
    private val gebruikteSessieTokens = HashMap<String, Instant>()
    private val koppelingen = HashMap<String, Koppeling>()
    private val afgerond = HashMap<String, Afgerond>()
    private val registraties = HashMap<String, Registratie>()

    /**
     * Wisselt een `session_token` om voor een koppeling; `null` als het token al eens is gebruikt.
     * Dat wijst op een gelekte of opnieuw afgespeelde URL.
     */
    @Synchronized
    fun koppel(sessieToken: String, verlooptOp: Instant): String? {
        ruimOp()

        if (gebruikteSessieTokens.containsKey(sessieToken)) return null

        vereisRuimte("koppelingen", gebruikteSessieTokens.size + koppelingen.size + afgerond.size)

        gebruikteSessieTokens[sessieToken] = verlooptOp

        val id = token()

        koppelingen[id] = Koppeling(sessieToken, verlooptOp)

        return id
    }

    /** Het `session_token` achter een koppeling, zonder hem te verbruiken. */
    @Synchronized
    fun sessieToken(koppeling: String): String? {
        ruimOp()

        return koppelingen[koppeling]?.sessieToken
    }

    /** De doorverwijzing van een koppeling die al een keuze opleverde, binnen [HERHAALVENSTER]; zie [bevestig]. */
    @Synchronized
    fun afgerond(koppeling: String): String? {
        ruimOp()

        return afgerond[koppeling]?.terug
    }

    /**
     * Een voorlopige registratie; pas [bevestig] maakt hem de enige van zijn sleutel. Per client is
     * het aantal begrensd, want een self-signed sessie kost niets.
     */
    @Synchronized
    fun registreer(ontvanger: String, thumbprint: String, client: String, geldig: Duration): String {
        ruimOp()

        // Een vaste wachttijd: de werkelijke zou verraden wanneer een ander achter hetzelfde adres activeerde.
        if (registraties.values.count { it.client == client } >= MAX_PER_CLIENT) {
            log.warnf("Registraties: quotum per client vol (client %s)", vingerafdruk(client))

            throw OpslagVolException(Duration.ofSeconds(OPNIEUW_NA_SECONDEN))
        }

        vereisRuimte("registraties", registraties.size)

        val token = token()

        registraties[token] = Registratie(ontvanger, thumbprint, client, klok.instant().plus(geldig))

        return token
    }

    /**
     * Verbruikt de koppeling en maakt [token] de enige registratie van zijn sleutel, in één stap.
     * Geeft de doorverwijzing terug, of `null` als de koppeling niets meer oplevert.
     *
     * Bij een dubbele submit wint precies één verzoek. Het andere krijgt dezelfde doorverwijzing
     * terug: de browser toont het antwoord op het laatste verzoek, en dat moet het token van de
     * winnaar bevatten. Die herhaling werkt alleen binnen [HERHAALVENSTER]: wie de koppeling-id
     * later nog heeft, bijvoorbeeld uit een geplante cookie, haalt het token er niet meer mee op. Is
     * de registratie al weg, dan blijft de koppeling staan voor een nieuwe keuze.
     */
    @Synchronized
    fun bevestig(koppeling: String, token: String, terug: String): String? {
        val open = koppelingen[koppeling]
        val nieuw = registraties[token]

        if (open == null || nieuw == null) {
            registraties.remove(token)

            return afgerond[koppeling]?.terug
        }

        koppelingen.remove(koppeling)
        afgerond[koppeling] = Afgerond(terug, minOf(open.verlooptOp, klok.instant().plus(HERHAALVENSTER)))
        registraties.entries.removeIf { it.key != token && it.value.thumbprint == nieuw.thumbprint }

        return terug
    }

    /** Voor een registratie waarvan het token de app nooit bereikte. */
    @Synchronized
    fun vergeet(token: String) {
        registraties.remove(token)
    }

    @Synchronized
    fun registratie(token: String): Registratie? {
        ruimOp()

        return registraties[token]
    }

    private fun ruimOp() {
        val nu = klok.instant()

        gebruikteSessieTokens.values.removeIf { !it.isAfter(nu) }
        koppelingen.values.removeIf { !it.verlooptOp.isAfter(nu) }
        afgerond.values.removeIf { !it.verlooptOp.isAfter(nu) }
        registraties.values.removeIf { !it.verlooptOp.isAfter(nu) }
    }

    private fun vereisRuimte(opslag: String, aantal: Int) {
        if (aantal < MAX_AANTAL) return

        log.warnf("Registraties: opslag %s vol (%d); het totaal voor alle clients is bereikt", opslag, aantal)

        throw OpslagVolException(Duration.ofSeconds(OPNIEUW_NA_SECONDEN))
    }

    // 32 tekens uit 62 is ruim 190 bit; de standaard eist voor het registration_token ten minste 22
    // tekens (4.4.1, stap 10).
    private fun token(): String = buildString(TOKEN_LENGTE) {
        repeat(TOKEN_LENGTE) { append(TOKEN_TEKENS[willekeur.nextInt(TOKEN_TEKENS.length)]) }
    }

    /** Binnen één run herkenbaar in de log, zonder het adres zelf te noemen. */
    private fun vingerafdruk(client: String): String {
        val mac = Mac.getInstance(HMAC).apply { init(logsleutel) }

        return HexFormat.of().formatHex(mac.doFinal(client.toByteArray())).take(VINGERAFDRUK_TEKENS)
    }

    companion object {
        private val log: Logger = Logger.getLogger(Registraties::class.java)

        /** Zo lang geeft een dubbele submit nog dezelfde doorverwijzing. */
        val HERHAALVENSTER: Duration = Duration.ofSeconds(10)

        const val MAX_AANTAL = 10_000
        const val MAX_PER_CLIENT = 50
        private const val OPNIEUW_NA_SECONDEN = 60L
        private const val TOKEN_LENGTE = 32
        private const val TOKEN_TEKENS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private const val VINGERAFDRUK_TEKENS = 12
        private const val LOGSLEUTEL_BYTES = 32
        private const val HMAC = "HmacSHA256"
    }
}
