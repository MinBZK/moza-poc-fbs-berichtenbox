package nl.rijksoverheid.moz.fbs.rechtstreeks

import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Wie een magazijnsessie heeft: een ontvanger uit een credential, of alleen een app-sleutel. */
sealed interface Identiteit {

    /** Uit een gecontroleerd credential van een App Manager; [thumbprint] is die van `cnf.jwk`. */
    class Ontvanger(val ontvanger: Identificatienummer, val thumbprint: String) : Identiteit {
        override fun toString(): String = "Ontvanger(type=${ontvanger.type})"
    }

    /** Uit een self-signed certificaat: bekend is welke sleutel, nog niet van wie. */
    class AppSleutel(val publicKey: ECPublicKey, val thumbprint: String) : Identiteit {
        override fun toString(): String = "AppSleutel(thumbprint=$thumbprint)"
    }
}

/**
 * Een opgezette sessie. [aesSleutel] versleutelt elk antwoord; [token] alleen geeft dus geen
 * leesbare gegevens. `toString` laat beide weg. [verlooptOp] is altijd een hele seconde: alleen
 * [Verifier] maakt een sessie, en die rondt af, zodat het einde gelijk is aan `geldigTot` in het
 * antwoord aan de app.
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
     * Gooit [OpslagVolException] als er te veel sessies zijn: in totaal, voor [houder] (per Verifier
     * en over alle Verifiers samen), of voor [client] over alle Verifiers samen. [houder] is wie het
     * quotum draagt: bij een credential de app-sleutel, anders de client.
     */
    fun bewaar(sessie: Sessie, houder: String, client: String)

    /** De sessie, of `null` als hij onbekend of verlopen is. */
    fun zoek(verifier: String, token: String): Sessie?

    /**
     * Of [token] kort geleden nog een sessie bij [verifier] had die nu verlopen is. Alleen voor de
     * reden in de log: een `false` zegt niet dat het token verzonnen is, want de opslag onthoudt
     * verlopen tokens begrensd.
     */
    fun verlopen(verifier: String, token: String): Boolean
}

/**
 * Begrensde opslag in het geheugen, voor een dienst met één instantie (simulator, App Manager).
 *
 * Quota gelden per Verifier, zodat een browser die bij 100 gesimuleerde magazijnen tegelijk een
 * challenge vraagt, per magazijn één keer telt. Plafonds over alle Verifiers samen houden de
 * simulator binnen de perken: per client voor challenges en sessies, en per houder voor sessies.
 * Het plafond per client voor sessies is ruim, zodat een kantooradres of CGNAT met veel
 * ondernemers past. Het totaal beschermt het geheugen; met de standaardwaarden vullen 25 adressen
 * het, en daartegen helpt alleen een stateless nonce (zie [ChallengeOpslag]).
 *
 * De aanroeper bepaalt `client`. Achter een ingress is dat het adres dat de vertrouwde proxy
 * doorgeeft, nooit een waarde die de client zelf kan zetten; anders deelt iedereen het adres van de
 * proxy. Niet `quarkus.http.proxy.proxy-address-forwarding`: dat neemt de eerste waarde van
 * `X-Forwarded-For`, en die kiest de client. Een IPv6-adres telt per /64.
 *
 * Een verlopen sessie laat een vingerafdruk van haar token achter, [verlopenOnthouden] lang en
 * hoogstens [maxVerlopen] tegelijk, zodat de log een token dat net verliep apart kan noemen (zie
 * [verlopen]). Zonder die verzameling ruimt de eerste aanroep van wie dan ook de sessie op, en is
 * "verlopen" niet meer te zien. Ze telt voor geen enkel quotum mee. Het standaardplafond is zes
 * keer [maxSessies]: zoveel sessies van de kortste soort (tien minuten, self-signed) verlopen er
 * onder volle last in een uur, dus houdt de opslag elk verlopen token de hele [verlopenOnthouden].
 */
class GeheugenOpslag(
    private val klok: Clock,
    private val maxChallenges: Int = 10_000,
    private val maxChallengesPerClient: Int = 20,
    private val maxSessies: Int = 50_000,
    private val maxSessiesPerHouder: Int = 20,
    private val maxPerClientOverVerifiers: Int = 200,
    private val maxSessiesPerHouderOverVerifiers: Int = 2_000,
    private val maxSessiesPerClientOverVerifiers: Int = 2_000,
    private val maxVerlopen: Int = maxSessies * VERLOPEN_PER_SESSIEPLEK,
    private val verlopenOnthouden: Duration = Duration.ofHours(1),
) : ChallengeOpslag, SessieOpslag {

    init {
        val grenzen = listOf(
            maxChallenges,
            maxChallengesPerClient,
            maxSessies,
            maxSessiesPerHouder,
            maxPerClientOverVerifiers,
            maxSessiesPerHouderOverVerifiers,
            maxSessiesPerClientOverVerifiers,
            maxVerlopen,
        )

        require(grenzen.all { it > 0 }) { "elke grens moet groter dan nul zijn" }
        require(verlopenOnthouden > Duration.ZERO) { "de bewaartermijn van verlopen tokens moet groter dan nul zijn" }
    }

    private class Openstaand(val verifier: String, val client: String, val sleutel: ECPrivateKey, val verlooptOp: Instant)

    private class Bewaard(val houder: String, val client: String, val sessie: Sessie, val afdruk: Vingerafdruk)

    /**
     * De eerste 128 bit van de SHA-256 van een token, per Verifier. Het token zelf blijft niet
     * bewaard: ook verlopen hoort het niet in een heap-dump. 128 bit volstaat om een token van ruim
     * 190 bit terug te herkennen, en houdt een vermelding klein.
     */
    private data class Vingerafdruk(val verifier: String, val hoog: Long, val laag: Long) {
        companion object {
            fun van(verifier: String, token: String): Vingerafdruk {
                val hash = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

                return Vingerafdruk(verifier, hash.long, hash.long)
            }
        }
    }

    private val challenges = HashMap<Pair<String, String>, Openstaand>()
    private val sessies = HashMap<Pair<String, String>, Bewaard>()

    /**
     * Wanneer de sessie bij een vingerafdruk verliep, oplopend. [ruimOp] voegt in volgorde van
     * verlopen toe, en wat er in een latere ronde bij komt, verliep na het moment van de vorige:
     * snoeien kan dus vanaf de kop. Dat geldt zolang de klok niet terugloopt; doet hij dat, dan
     * blijft een vermelding hooguit iets langer staan, en [verlopen] toetst de termijn zelf.
     */
    private val verlopenTokens = LinkedHashMap<Vingerafdruk, Instant>()

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

    // Geen @Synchronized op de methode: de hash hoort buiten de lock, die alle Verifiers delen.
    override fun bewaar(sessie: Sessie, houder: String, client: String) {
        val afdruk = Vingerafdruk.van(sessie.verifier, sessie.token)

        synchronized(this) { bewaar(sessie, houder, client, afdruk) }
    }

    private fun bewaar(sessie: Sessie, houder: String, client: String, afdruk: Vingerafdruk) {
        val nu = ruimOp()
        val vanHouder = sessies.values.filter { it.houder == houder }
        val bijVerifier = vanHouder.filter { it.sessie.verifier == sessie.verifier }
        val vanClient = sessies.values.filter { it.client == client }

        if (bijVerifier.size >= maxSessiesPerHouder) throw OpslagVolException(wachttijd(bijVerifier.map { it.sessie.verlooptOp }, nu))

        if (vanHouder.size >= maxSessiesPerHouderOverVerifiers) {
            throw OpslagVolException(wachttijd(vanHouder.map { it.sessie.verlooptOp }, nu))
        }

        if (vanClient.size >= maxSessiesPerClientOverVerifiers) {
            throw OpslagVolException(wachttijd(vanClient.map { it.sessie.verlooptOp }, nu))
        }

        if (sessies.size >= maxSessies) throw OpslagVolException(wachttijd(sessies.values.map { it.sessie.verlooptOp }, nu))

        sessies[sessie.verifier to sessie.token] = Bewaard(houder, client, sessie, afdruk)
    }

    @Synchronized
    override fun zoek(verifier: String, token: String): Sessie? {
        ruimOp()

        return sessies[verifier to token]?.sessie
    }

    /**
     * Zonder [ruimOp]: dit volgt op een gemiste [zoek], die net heeft opgeruimd, en een tweede ronde
     * zou een verzonnen token dubbel zo duur maken onder de lock die alle Verifiers delen.
     */
    override fun verlopen(verifier: String, token: String): Boolean {
        val afdruk = Vingerafdruk.van(verifier, token)

        synchronized(this) {
            val nu = klok.instant()

            val verlopenOp = sessies[verifier to token]?.sessie?.verlooptOp ?: verlopenTokens[afdruk] ?: return false

            return !verlopenOp.isAfter(nu) && verlopenOp.plus(verlopenOnthouden).isAfter(nu)
        }
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

        val afgelopen = sessies.entries
            .filter { !it.value.sessie.verlooptOp.isAfter(nu) }
            .sortedBy { it.value.sessie.verlooptOp }

        afgelopen.forEach { (sleutel, bewaard) ->
            sessies.remove(sleutel)
            verlopenTokens[bewaard.afdruk] = bewaard.sessie.verlooptOp
        }

        val oudsteEerst = verlopenTokens.values.iterator()

        while (oudsteEerst.hasNext() && !oudsteEerst.next().plus(verlopenOnthouden).isAfter(nu)) oudsteEerst.remove()

        // Een vermelding die zo wegvalt, verliest alleen het onderscheid en wordt weer een onbekend token.
        val teVeel = verlopenTokens.size - maxVerlopen
        val teSchrappen = verlopenTokens.keys.iterator()

        repeat(teVeel.coerceAtLeast(0)) {
            teSchrappen.next()
            teSchrappen.remove()
        }

        return nu
    }

    private fun wachttijd(momenten: List<Instant>, nu: Instant): Duration =
        Duration.between(nu, maxOf(momenten.minOrNull() ?: nu, nu.plusSeconds(1)))

    private companion object {
        const val VERLOPEN_PER_SESSIEPLEK = 6
    }
}
