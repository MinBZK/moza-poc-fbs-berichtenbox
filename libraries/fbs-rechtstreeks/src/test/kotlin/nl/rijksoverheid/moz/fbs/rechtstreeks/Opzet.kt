package nl.rijksoverheid.moz.fbs.rechtstreeks

import org.jose4j.json.JsonUtil
import java.security.KeyPair
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64

/** Een stelsel in het klein: een App Manager, een app met een credential, en Verifiers die de klok delen. */
class Opzet(private val willekeur: java.security.SecureRandom = java.security.SecureRandom()) {

    var nu: Instant = Instant.parse("2026-10-27T10:00:00Z")

    val klok = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = nu
    }

    val opslag = GeheugenOpslag(klok)
    val magazijnSleutel: KeyPair = TestApp.sleutelpaar()
    var magazijnSleutelGeldigTot: Instant = nu.plus(Duration.ofDays(30))
    val appManager: KeyPair = TestApp.sleutelpaar()
    var appManagerGeldigTot: Instant = nu.plus(Duration.ofDays(30))
    val app: KeyPair = TestApp.sleutelpaar()

    val appManagers = AppManagerSleutels { iss, kid ->
        if (iss == APP_MANAGER_OIN && kid == APP_MANAGER_KID) {
            AppManagerSleutel(appManager.public as ECPublicKey, appManagerGeldigTot)
        } else {
            null
        }
    }

    fun verifier(oin: String = MAGAZIJN_OIN, opslag: GeheugenOpslag = this.opslag): Verifier = Verifier(
        oin = oin,
        sleutel = VerifierSleutel(MAGAZIJN_KID, magazijnSleutel.private as ECPrivateKey, magazijnSleutelGeldigTot),
        appManagers = appManagers,
        scope = SCOPE,
        challenges = opslag,
        sessies = opslag,
        klok = klok,
        willekeur = willekeur,
    )

    fun vcClaims(): MutableMap<String, Any> = mutableMapOf(
        "cnf" to mapOf("jwk" to TestApp.jwk(app.public as ECPublicKey)),
        "scope" to SCOPE,
        "iss" to APP_MANAGER_OIN,
        "iat" to nu.epochSecond,
        "nbf" to nu.epochSecond,
        "exp" to nu.plus(Duration.ofHours(1)).epochSecond,
        "ontvanger" to ONTVANGER,
    )

    fun vc(claims: Map<String, Any> = vcClaims(), sleutel: PrivateKey = appManager.private, kid: String = APP_MANAGER_KID): String =
        TestApp.es256(claims, sleutel, kid)

    fun selfSigned(sleutel: KeyPair = app, sub: String = Verifier.CERTIFICAAT_SELF_SIGNED): String =
        TestApp.es256(mapOf("sub" to sub, "cnf" to mapOf("jwk" to TestApp.jwk(sleutel.public as ECPublicKey))), sleutel.private)

    fun nonceHandtekening(
        nonce: String,
        aud: String = MAGAZIJN_OIN,
        sleutel: PrivateKey = app.private,
        sub: String = "challenge_response",
    ): String = TestApp.es256(mapOf("sub" to sub, "aud" to aud, "nonce" to nonce), sleutel)

    class Challenge(val nonce: String, val tijdelijk: ECPublicKey, val claims: Map<String, Any>, val compact: String)

    /** Vraagt een challenge op en controleert hem zoals een app dat doet. */
    fun challenge(verifier: Verifier, client: String = CLIENT): Challenge {
        val compact = verifier.maakChallenge(client)
        val claims = TestApp.verifieerJws(compact, magazijnSleutel.public)
        val tijdelijk = TestApp.publicKeyUitDer(claims["ephemeral_organization_ec_public_key"] as String)

        return Challenge(claims["nonce"] as String, tijdelijk, claims, compact)
    }

    fun presentatie(
        challenge: Challenge,
        certificaat: String = vc(),
        soort: String = Verifier.CERTIFICAAT_APP_MANAGER,
        aesSleutel: ByteArray = TestApp.aesSleutel(),
    ): MutableMap<String, Any> = mutableMapOf(
        "app_nonce_signature" to nonceHandtekening(challenge.nonce),
        "certificate_type" to soort,
        "certificate" to certificaat,
        "session_aes_key" to Base64.getEncoder().encodeToString(aesSleutel),
    )

    fun versleuteld(challenge: Challenge, presentatie: Map<String, Any>): String =
        TestApp.jweEcdh(JsonUtil.toJson(presentatie), challenge.tijdelijk)

    /**
     * Een geldige presentatie, opgevuld tot de JWE een lengte in [lengtes] heeft. Niet elke lengte
     * is te maken: base64url zonder opvulling kent geen lengte van 1 modulo 4, dus een bereik van
     * vier tekens volstaat altijd.
     */
    fun opgevuld(challenge: Challenge, lengtes: IntRange): String {
        val basis = presentatie(challenge)
        val leeg = TestApp.jweEcdh(JsonUtil.toJson(basis + ("opvulling" to "")), challenge.tijdelijk).length
        val opvulling = (lengtes.first - leeg) * 3 / 4

        return (-3..6).asSequence()
            .map { TestApp.jweEcdh(JsonUtil.toJson(basis + ("opvulling" to "a".repeat(opvulling + it))), challenge.tijdelijk) }
            .first { it.length in lengtes }
    }

    /** Een volledige sessie met credential; geeft de sessie en de AES-sleutel van de app. */
    fun sessie(verifier: Verifier, claims: Map<String, Any> = vcClaims()): Pair<UitgegevenSessie, ByteArray> {
        val challenge = challenge(verifier)
        val aes = TestApp.aesSleutel()
        val presentatie = presentatie(challenge, vc(claims), aesSleutel = aes)

        return verifier.voltooiMetCredential(challenge.nonce, versleuteld(challenge, presentatie), CLIENT) to aes
    }

    companion object {
        const val MAGAZIJN_OIN = "00000001234567890001"
        const val ANDER_MAGAZIJN_OIN = "00000001234567890002"
        const val MAGAZIJN_KID = "magazijn-1"
        const val APP_MANAGER_OIN = "00000000000000001100"
        const val APP_MANAGER_KID = "app-manager-1"
        const val SCOPE = "nl.rijksoverheid.moz.fbs.berichten"
        const val ONTVANGER = "KVK:55667788"
        const val CLIENT = "192.0.2.1"
    }
}
