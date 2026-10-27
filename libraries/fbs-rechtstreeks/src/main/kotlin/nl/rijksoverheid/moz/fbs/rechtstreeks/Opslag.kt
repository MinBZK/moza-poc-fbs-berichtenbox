package nl.rijksoverheid.moz.fbs.rechtstreeks

import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Wie een magazijnsessie heeft: een ontvanger uit een credential, of alleen een app-sleutel. */
sealed interface Identiteit {

    /** Uit een gecontroleerd credential van een App Manager. */
    class Ontvanger(val ontvanger: Identificatienummer) : Identiteit {
        override fun toString(): String = "Ontvanger(type=${ontvanger.type})"
    }

    /** Uit een self-signed certificaat: bekend is welke sleutel, nog niet van wie. */
    class AppSleutel(val publicKey: ECPublicKey, val thumbprint: String) : Identiteit {
        override fun toString(): String = "AppSleutel(thumbprint=$thumbprint)"
    }
}

/**
 * Een opgezette sessie. [aesSleutel] versleutelt elk antwoord; [token] alleen geeft dus geen
 * leesbare gegevens. `toString` laat beide weg.
 */
class Sessie internal constructor(
    val verifier: String,
    val token: String,
    internal val aesSleutel: ByteArray,
    val identiteit: Identiteit,
    val verlooptOp: Instant,
) {
    override fun toString(): String = "Sessie(verifier=$verifier, identiteit=$identiteit, verlooptOp=$verlooptOp)"
}

/**
 * Openstaande challenges: de tijdelijke private key per nonce, één keer op te halen.
 *
 * TODO(#1215): voor een dienst met meer replica's maakt de opslag de nonce zelf (stateless, met de
 * ingepakte sleutel erin); dan geeft [bewaar] de nonce terug in plaats van hem te krijgen.
 */
interface ChallengeOpslag {

    /** Gooit [OpslagVolException] als er te veel openstaan, in totaal of voor [client] bij [verifier]. */
    fun bewaar(verifier: String, nonce: String, client: String, sleutel: ECPrivateKey, verlooptOp: Instant)

    /** Haalt de sleutel op en verwijdert hem; `null` als hij onbekend, verlopen of al gebruikt is. */
    fun neem(verifier: String, nonce: String): ECPrivateKey?
}

/** Opgezette sessies, per Verifier, zodat een token van het ene magazijn niet bij een ander werkt. */
interface SessieOpslag {

    /** Gooit [OpslagVolException] als er te veel sessies zijn, in totaal of voor [client] bij deze Verifier. */
    fun bewaar(sessie: Sessie, client: String)

    /** De sessie, of `null` als hij onbekend of verlopen is. */
    fun zoek(verifier: String, token: String): Sessie?
}

/**
 * Begrensde opslag in het geheugen, voor een dienst met één instantie (simulator, App Manager).
 *
 * De quota gelden per Verifier en client: een browser die bij 100 gesimuleerde magazijnen tegelijk
 * een challenge vraagt, telt per magazijn één keer. Het totaal beschermt het geheugen. Wie met veel
 * client-adressen tegelijk komt, kan dat totaal vullen; daartegen helpt alleen een stateless nonce
 * (zie [ChallengeOpslag]). De aanroeper leidt `client` af uit het verbindingsadres, niet uit een
 * header die de client zelf zet, en voegt IPv6-adressen samen per /64.
 */
class GeheugenOpslag(
    private val klok: Clock,
    private val maxChallenges: Int = 10_000,
    private val maxChallengesPerClient: Int = 20,
    private val maxSessies: Int = 10_000,
    private val maxSessiesPerClient: Int = 20,
) : ChallengeOpslag, SessieOpslag {

    init {
        require(minOf(maxChallenges, maxChallengesPerClient, maxSessies, maxSessiesPerClient) > 0) {
            "elke grens moet groter dan nul zijn"
        }
    }

    private class Openstaand(val client: String, val sleutel: ECPrivateKey, val verlooptOp: Instant)

    private class Bewaard(val client: String, val sessie: Sessie)

    private val challenges = HashMap<Pair<String, String>, Openstaand>()
    private val sessies = HashMap<Pair<String, String>, Bewaard>()

    @Synchronized
    override fun bewaar(verifier: String, nonce: String, client: String, sleutel: ECPrivateKey, verlooptOp: Instant) {
        val nu = ruimOp()
        val vanClient = challenges.filter { (plek, waarde) -> plek.first == verifier && waarde.client == client }.values

        // Bij een vol quotum telt wanneer een challenge van díe client vrijkomt, niet de eerste van iemand anders.
        if (vanClient.size >= maxChallengesPerClient) throw OpslagVolException(wachttijd(vanClient.map { it.verlooptOp }, nu))

        if (challenges.size >= maxChallenges) throw OpslagVolException(wachttijd(challenges.values.map { it.verlooptOp }, nu))

        challenges[verifier to nonce] = Openstaand(client, sleutel, verlooptOp)
    }

    @Synchronized
    override fun neem(verifier: String, nonce: String): ECPrivateKey? {
        ruimOp()

        return challenges.remove(verifier to nonce)?.sleutel
    }

    @Synchronized
    override fun bewaar(sessie: Sessie, client: String) {
        val nu = ruimOp()
        val vanClient = sessies.values.filter { it.client == client && it.sessie.verifier == sessie.verifier }

        if (vanClient.size >= maxSessiesPerClient) throw OpslagVolException(wachttijd(vanClient.map { it.sessie.verlooptOp }, nu))

        if (sessies.size >= maxSessies) throw OpslagVolException(wachttijd(sessies.values.map { it.sessie.verlooptOp }, nu))

        sessies[sessie.verifier to sessie.token] = Bewaard(client, sessie)
    }

    @Synchronized
    override fun zoek(verifier: String, token: String): Sessie? {
        ruimOp()

        return sessies[verifier to token]?.sessie
    }

    /**
     * Verwijdert wat verlopen is, bij elke aanroep: een tijdelijke private key of sessiesleutel hoort
     * na zijn tijd niet in het geheugen te blijven, ook als er geen nieuw verkeer komt dat hem verdringt.
     */
    private fun ruimOp(): Instant {
        val nu = klok.instant()

        challenges.values.removeIf { !it.verlooptOp.isAfter(nu) }

        sessies.values.removeIf { bewaard ->
            val verlopen = !bewaard.sessie.verlooptOp.isAfter(nu)

            // Best effort: een kopie die de JVM elders hield, blijft buiten bereik.
            if (verlopen) bewaard.sessie.aesSleutel.fill(0)

            verlopen
        }

        return nu
    }

    private fun wachttijd(momenten: List<Instant>, nu: Instant): Duration =
        Duration.between(nu, maxOf(momenten.minOrNull() ?: nu, nu.plusSeconds(1)))
}
