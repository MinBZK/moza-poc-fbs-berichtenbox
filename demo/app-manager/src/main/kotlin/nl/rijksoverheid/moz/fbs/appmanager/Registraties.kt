package nl.rijksoverheid.moz.fbs.appmanager

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.rechtstreeks.OpslagVolException
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Een gekozen persona, gebonden aan de sleutel van de app die erom vroeg. */
class Registratie(val ontvanger: String, val thumbprint: String, val client: String, val verlooptOp: Instant) {
    override fun toString(): String = "Registratie(thumbprint=$thumbprint, verlooptOp=$verlooptOp)"
}

/**
 * Wat de App Manager tussen de stappen van het activeren onthoudt, in het geheugen en begrensd:
 *
 * - een **koppeling**: het `session_token` uit de URL van `/register_app`, omgewisseld voor een
 *   cookie. Het token werkt daarvoor één keer, zodat een gelekte URL niets meer opent;
 * - een **registratie**: het `registration_token` met de gekozen ontvanger en de thumbprint van de
 *   sleutel van de app. Met dat token haalt alleen die sleutel een (vers) credential op.
 */
@ApplicationScoped
class Registraties(private val klok: Clock) {

    private class Koppeling(val sessieToken: String, val verlooptOp: Instant)

    private val willekeur = SecureRandom()
    private val gebruikteSessieTokens = HashMap<String, Instant>()
    private val koppelingen = HashMap<String, Koppeling>()
    private val registraties = HashMap<String, Registratie>()

    /** Wisselt een `session_token` om voor een koppeling; `null` als het token al eens is gebruikt. */
    @Synchronized
    fun koppel(sessieToken: String, verlooptOp: Instant): String? {
        ruimOp()

        if (gebruikteSessieTokens.containsKey(sessieToken)) return null

        vereisRuimte(gebruikteSessieTokens.size + koppelingen.size)

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

    /**
     * Een voorlopige registratie; pas [bevestig] maakt hem de enige van zijn sleutel. Per client is
     * het aantal begrensd, want een self-signed sessie kost niets.
     */
    @Synchronized
    fun registreer(ontvanger: String, thumbprint: String, client: String, geldig: Duration): String {
        val nu = klok.instant()

        ruimOp()

        val vanClient = registraties.values.filter { it.client == client }

        if (vanClient.size >= MAX_PER_CLIENT) {
            throw OpslagVolException(Duration.between(nu, maxOf(vanClient.minOf { it.verlooptOp }, nu.plusSeconds(1))))
        }

        vereisRuimte(registraties.size)

        val token = token()

        registraties[token] = Registratie(ontvanger, thumbprint, client, nu.plus(geldig))

        return token
    }

    /**
     * Verbruikt de koppeling en maakt [token] de enige registratie van zijn sleutel, in één stap. Bij
     * een dubbele submit wint zo precies één verzoek; het andere krijgt `false` en zijn registratie
     * verdwijnt, in plaats van dat het de registratie van het winnende verzoek verdringt.
     */
    @Synchronized
    fun bevestig(koppeling: String, token: String): Boolean {
        val nieuw = registraties[token]

        if (koppelingen.remove(koppeling) == null || nieuw == null) {
            registraties.remove(token)

            return false
        }

        registraties.entries.removeIf { it.key != token && it.value.thumbprint == nieuw.thumbprint }

        return true
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
        registraties.values.removeIf { !it.verlooptOp.isAfter(nu) }
    }

    private fun vereisRuimte(aantal: Int) {
        if (aantal >= MAX_AANTAL) throw OpslagVolException(Duration.ofSeconds(OPNIEUW_NA_SECONDEN))
    }

    // 32 tekens uit 62 is ruim 190 bit; de standaard eist voor het registration_token ten minste 22.
    private fun token(): String = buildString(TOKEN_LENGTE) {
        repeat(TOKEN_LENGTE) { append(TOKEN_TEKENS[willekeur.nextInt(TOKEN_TEKENS.length)]) }
    }

    private companion object {
        const val MAX_AANTAL = 10_000
        const val MAX_PER_CLIENT = 50
        const val OPNIEUW_NA_SECONDEN = 60L
        const val TOKEN_LENGTE = 32
        const val TOKEN_TEKENS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
