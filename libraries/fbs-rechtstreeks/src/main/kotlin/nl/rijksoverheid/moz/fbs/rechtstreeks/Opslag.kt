package nl.rijksoverheid.moz.fbs.rechtstreeks

import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Wie een magazijnsessie heeft: een ontvanger uit een credential, of alleen een app-sleutel. */
sealed interface Identiteit {

    /** Uit een gecontroleerd credential van een App Manager; [appSleutel] is de thumbprint van `cnf.jwk`. */
    class Ontvanger(val ontvanger: Identificatienummer, val appSleutel: String) : Identiteit {
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

    /** Gooit [OpslagVolException] als er te veel openstaan, in totaal of voor [client]. */
    fun bewaar(verifier: String, nonce: String, client: String, sleutel: ECPrivateKey, verlooptOp: Instant)

    /** Haalt de sleutel op en verwijdert hem; `null` als hij onbekend, verlopen of al gebruikt is. */
    fun neem(verifier: String, nonce: String): ECPrivateKey?
}

/**
 * Opgezette sessies, per Verifier, zodat een token van het ene magazijn niet bij een ander werkt.
 *
 * TODO(#1215): voor een dienst met meer replica's moet een sessie geserialiseerd kunnen worden, met
 * de AES-sleutel ingepakt.
 */
interface SessieOpslag {

    /**
     * Gooit [OpslagVolException] als er te veel sessies zijn, in totaal of voor [houder] bij deze
     * Verifier. [houder] is wie het quotum draagt: bij een credential de app-sleutel, anders de client.
     */
    fun bewaar(sessie: Sessie, houder: String)

    /** De sessie, of `null` als hij onbekend of verlopen is. */
    fun zoek(verifier: String, token: String): Sessie?
}

/**
 * Begrensde opslag in het geheugen, voor een dienst met één instantie (simulator, App Manager).
 *
 * Quota gelden per Verifier, zodat een browser die bij 100 gesimuleerde magazijnen tegelijk een
 * challenge vraagt, per magazijn één keer telt; een plafond per client over alle Verifiers samen
 * houdt de simulator binnen de perken. Het totaal beschermt het geheugen. Wie met veel adressen
 * tegelijk komt, kan dat totaal vullen; daartegen helpt alleen een stateless nonce (zie
 * [ChallengeOpslag]).
 *
 * De aanroeper bepaalt `client`. Achter een ingress is dat het adres dat de vertrouwde proxy
 * doorgeeft (Quarkus: `quarkus.http.proxy.proxy-address-forwarding` met `trusted-proxies`), nooit
 * een header die de client zelf kan zetten; anders deelt iedereen het adres van de proxy. Een
 * IPv6-adres telt per /64.
 */
class GeheugenOpslag(
    private val klok: Clock,
    private val maxChallenges: Int = 10_000,
    private val maxChallengesPerClient: Int = 20,
    private val maxSessies: Int = 10_000,
    private val maxSessiesPerHouder: Int = 20,
    private val maxPerClientOverVerifiers: Int = 200,
) : ChallengeOpslag, SessieOpslag {

    init {
        val grenzen = listOf(maxChallenges, maxChallengesPerClient, maxSessies, maxSessiesPerHouder, maxPerClientOverVerifiers)

        require(grenzen.all { it > 0 }) { "elke grens moet groter dan nul zijn" }
    }

    private class Openstaand(val verifier: String, val client: String, val sleutel: ECPrivateKey, val verlooptOp: Instant)

    private class Bewaard(val houder: String, val sessie: Sessie)

    private val challenges = HashMap<Pair<String, String>, Openstaand>()
    private val sessies = HashMap<Pair<String, String>, Bewaard>()

    @Synchronized
    override fun bewaar(verifier: String, nonce: String, client: String, sleutel: ECPrivateKey, verlooptOp: Instant) {
        val nu = ruimOp()
        val vanClient = challenges.values.filter { it.client == client }
        val bijVerifier = vanClient.filter { it.verifier == verifier }

        // Bij een vol quotum telt wanneer een challenge van díe client vrijkomt, niet de eerste van iemand anders.
        if (bijVerifier.size >= maxChallengesPerClient) throw OpslagVolException(wachttijd(bijVerifier.map { it.verlooptOp }, nu))

        if (vanClient.size >= maxPerClientOverVerifiers) throw OpslagVolException(wachttijd(vanClient.map { it.verlooptOp }, nu))

        if (challenges.size >= maxChallenges) throw OpslagVolException(wachttijd(challenges.values.map { it.verlooptOp }, nu))

        challenges[verifier to nonce] = Openstaand(verifier, client, sleutel, verlooptOp)
    }

    @Synchronized
    override fun neem(verifier: String, nonce: String): ECPrivateKey? {
        ruimOp()

        return challenges.remove(verifier to nonce)?.sleutel
    }

    @Synchronized
    override fun bewaar(sessie: Sessie, houder: String) {
        val nu = ruimOp()
        val vanHouder = sessies.values.filter { it.houder == houder && it.sessie.verifier == sessie.verifier }

        if (vanHouder.size >= maxSessiesPerHouder) throw OpslagVolException(wachttijd(vanHouder.map { it.sessie.verlooptOp }, nu))

        if (sessies.size >= maxSessies) throw OpslagVolException(wachttijd(sessies.values.map { it.sessie.verlooptOp }, nu))

        sessies[sessie.verifier to sessie.token] = Bewaard(houder, sessie)
    }

    @Synchronized
    override fun zoek(verifier: String, token: String): Sessie? {
        ruimOp()

        return sessies[verifier to token]?.sessie
    }

    /**
     * Verwijdert wat verlopen is, bij elke aanroep, zodat een tijdelijke private key of sessiesleutel
     * niet in het geheugen blijft wachten op nieuw verkeer. De sleutel zelf wordt niet gewist: een
     * verzoek dat de sessie al had, gebruikt hem misschien nog, en een gewiste sleutel zou het
     * antwoord onder nullen versleutelen. [Verifier] weigert een verlopen sessie zelf.
     */
    private fun ruimOp(): Instant {
        val nu = klok.instant()

        challenges.values.removeIf { !it.verlooptOp.isAfter(nu) }
        sessies.values.removeIf { !it.sessie.verlooptOp.isAfter(nu) }

        return nu
    }

    private fun wachttijd(momenten: List<Instant>, nu: Instant): Duration =
        Duration.between(nu, maxOf(momenten.minOrNull() ?: nu, nu.plusSeconds(1)))
}
