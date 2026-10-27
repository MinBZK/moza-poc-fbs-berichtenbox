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

/** Openstaande challenges: de tijdelijke private key per nonce, één keer op te halen. */
interface ChallengeOpslag {

    /** Gooit [OpslagVolException] als er te veel openstaan, in totaal of voor [client]. */
    fun bewaar(verifier: String, nonce: String, client: String, sleutel: ECPrivateKey, verlooptOp: Instant)

    /** Haalt de sleutel op en verwijdert hem; `null` als hij onbekend, verlopen of al gebruikt is. */
    fun neem(verifier: String, nonce: String): ECPrivateKey?
}

/** Opgezette sessies, per Verifier, zodat een token van het ene magazijn niet bij een ander werkt. */
interface SessieOpslag {

    /** Gooit [OpslagVolException] als er te veel sessies zijn. */
    fun bewaar(sessie: Sessie)

    /** De sessie, of `null` als hij onbekend of verlopen is. */
    fun zoek(verifier: String, token: String): Sessie?
}

/**
 * Begrensde opslag in het geheugen, voor een dienst met één instantie (simulator, App Manager).
 * `challenge` is anoniem en kan vanuit elke webpagina aangeroepen worden. Het quotum per client
 * zorgt dat wie de opslag probeert te vullen, vooral zichzelf blokkeert.
 */
class GeheugenOpslag(
    private val klok: Clock,
    private val maxChallenges: Int = 10_000,
    private val maxChallengesPerClient: Int = 20,
    private val maxSessies: Int = 10_000,
) : ChallengeOpslag, SessieOpslag {

    private class Openstaand(val client: String, val sleutel: ECPrivateKey, val verlooptOp: Instant)

    private val challenges = HashMap<Pair<String, String>, Openstaand>()
    private val sessies = HashMap<Pair<String, String>, Sessie>()

    @Synchronized
    override fun bewaar(verifier: String, nonce: String, client: String, sleutel: ECPrivateKey, verlooptOp: Instant) {
        val nu = klok.instant()

        challenges.values.removeIf { !it.verlooptOp.isAfter(nu) }

        val vanClient = challenges.values.filter { it.client == client }

        // Bij een vol quotum telt wanneer een challenge van díe client vrijkomt, niet de eerste van iemand anders.
        if (vanClient.size >= maxChallengesPerClient) {
            throw OpslagVolException(Duration.between(nu, vroegste(vanClient.map { it.verlooptOp }, nu)))
        }

        if (challenges.size >= maxChallenges) {
            throw OpslagVolException(Duration.between(nu, vroegste(challenges.values.map { it.verlooptOp }, nu)))
        }

        challenges[verifier to nonce] = Openstaand(client, sleutel, verlooptOp)
    }

    @Synchronized
    override fun neem(verifier: String, nonce: String): ECPrivateKey? {
        val openstaand = challenges.remove(verifier to nonce) ?: return null

        return openstaand.sleutel.takeIf { openstaand.verlooptOp.isAfter(klok.instant()) }
    }

    @Synchronized
    override fun bewaar(sessie: Sessie) {
        val nu = klok.instant()

        sessies.values.removeIf { !it.verlooptOp.isAfter(nu) }

        if (sessies.size >= maxSessies) {
            throw OpslagVolException(Duration.between(nu, vroegste(sessies.values.map { it.verlooptOp }, nu)))
        }

        sessies[sessie.verifier to sessie.token] = sessie
    }

    @Synchronized
    override fun zoek(verifier: String, token: String): Sessie? =
        sessies[verifier to token]?.takeIf { it.verlooptOp.isAfter(klok.instant()) }

    private fun vroegste(momenten: List<Instant>, nu: Instant): Instant =
        maxOf(momenten.minOrNull() ?: nu, nu.plusSeconds(1))
}
