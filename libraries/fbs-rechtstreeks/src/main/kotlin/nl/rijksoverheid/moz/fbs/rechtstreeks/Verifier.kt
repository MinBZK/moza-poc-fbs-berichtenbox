package nl.rijksoverheid.moz.fbs.rechtstreeks

import org.jose4j.json.JsonUtil
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Een nieuwe sessie, met [antwoord]: de JWE die de app terugkrijgt op `complete`. */
class UitgegevenSessie(val sessie: Sessie, val antwoord: String)

/**
 * De Verifier-kant van `CreateSession` (FSC voor Burgers 4.3.4), voor één organisatie met OIN
 * [oin]. Een magazijn gebruikt [voltooiMetCredential]; de App Manager, die bij het activeren nog
 * niet weet wie de gebruiker is, gebruikt [voltooiSelfSigned]. Het zijn bewust twee ingangen: een
 * magazijn kan zo nooit per ongeluk een presentatie zonder vastgestelde identiteit aannemen.
 *
 * Elke afwijzing is een [AfgewezenException]; de aanroeper antwoordt daarop `401`, ongeacht de
 * reden. Is de opslag vol, dan volgt [OpslagVolException] en antwoordt de aanroeper `503`.
 */
class Verifier(
    private val oin: String,
    private val sleutel: VerifierSleutel,
    uitgevers: UitgeverSleutels,
    scope: String,
    private val challenges: ChallengeOpslag,
    private val sessies: SessieOpslag,
    private val klok: Clock,
    private val willekeur: SecureRandom = SecureRandom(),
) {

    private val credentials = CredentialControle(uitgevers, scope)

    /**
     * Stap 2 tot en met 9: een nonce en een tijdelijk sleutelpaar, de challenge getekend met de
     * sleutel van deze Verifier. [client] is de herkomst van het verzoek (bijvoorbeeld het IP-adres)
     * en telt mee voor het quotum.
     */
    fun maakChallenge(client: String): String {
        val nonce = Base64.getEncoder().encodeToString(ByteArray(NONCE_BYTES).also(willekeur::nextBytes))
        val tijdelijk = KeyPairGenerator.getInstance("EC").run {
            initialize(Jose.P256, willekeur)
            generateKeyPair()
        }

        challenges.bewaar(oin, nonce, client, tijdelijk.private as ECPrivateKey, klok.instant().plus(NONCE_GELDIG))

        return Jose.teken(
            mapOf(
                "sub" to "challenge",
                "nonce" to nonce,
                "ephemeral_organization_ec_public_key" to Base64.getEncoder().encodeToString(tijdelijk.public.encoded),
            ),
            sleutel,
        )
    }

    /** Stap 20 tot en met 26 voor een magazijn: alleen een credential van een App Manager. */
    fun voltooiMetCredential(nonce: String, versleuteldePresentatie: String): UitgegevenSessie {
        val nu = klok.instant()
        val presentatie = openPresentatie(nonce, versleuteldePresentatie, CERTIFICAAT_APP_MANAGER)
        val credential = credentials.controleer(presentatie.certificaat, nu)

        controleerNonceHandtekening(presentatie.nonceHandtekening, credential.appSleutel, nonce)

        val resterend = Duration.between(nu, credential.exp)

        return geefUit(presentatie.aesSleutel, Identiteit.Ontvanger(credential.ontvanger), minOf(SESSIE_GELDIG, resterend), nu)
    }

    /** Stap 20 tot en met 26 voor de App Manager: alleen een self-signed certificaat (4.4.1, stap 2). */
    fun voltooiSelfSigned(nonce: String, versleuteldePresentatie: String): UitgegevenSessie {
        val nu = klok.instant()
        val presentatie = openPresentatie(nonce, versleuteldePresentatie, CERTIFICAAT_SELF_SIGNED)
        val appSleutel = selfSignedSleutel(presentatie.certificaat)

        controleerNonceHandtekening(presentatie.nonceHandtekening, appSleutel, nonce)

        return geefUit(presentatie.aesSleutel, Identiteit.AppSleutel(appSleutel, Jose.thumbprint(appSleutel)), SESSIE_GELDIG, nu)
    }

    /** De sessie bij [token]; een onbekend of verlopen token wordt afgewezen. */
    fun sessie(token: String): Sessie =
        sessies.zoek(oin, token) ?: throw AfgewezenException(Afwijzing.SESSIE_ONBEKEND)

    /** Versleutelt een antwoord onder de sessiesleutel (`dir`, `A256GCM`). */
    fun versleutel(sessie: Sessie, payload: String): String = Jose.versleutelDir(payload, sessie.aesSleutel)

    /** Ontsleutelt een verzoek dat de app onder de sessiesleutel versleutelde. */
    fun ontsleutel(sessie: Sessie, jwe: String): String = Jose.ontsleutelDir(jwe, sessie.aesSleutel)

    private class Presentatie(val nonceHandtekening: String, val certificaat: String, val aesSleutel: ByteArray)

    private fun openPresentatie(nonce: String, versleuteld: String, verwachtCertificaat: String): Presentatie {
        // De nonce is na deze regel verbruikt, ook als een controle hieronder faalt: herhaald
        // proberen met dezelfde challenge levert dus niets op.
        val tijdelijkeSleutel = challenges.neem(oin, nonce) ?: throw AfgewezenException(Afwijzing.NONCE_ONBEKEND)
        val velden = Jose.jsonObject(Jose.ontsleutelEcdh(versleuteld, tijdelijkeSleutel), Afwijzing.PRESENTATIE_ONLEESBAAR)

        val nonceHandtekening = velden["app_nonce_signature"] as? String
        val soort = velden["certificate_type"] as? String
        val certificaat = velden["certificate"] as? String
        val aesTekst = velden["session_aes_key"] as? String

        if (nonceHandtekening == null || soort == null || certificaat == null || aesTekst == null) {
            throw AfgewezenException(Afwijzing.PRESENTATIE_ONVOLLEDIG)
        }

        if (soort != verwachtCertificaat) throw AfgewezenException(Afwijzing.CERTIFICAATSOORT_ONJUIST)

        return Presentatie(nonceHandtekening, certificaat, aesSleutel(aesTekst))
    }

    private fun aesSleutel(tekst: String): ByteArray {
        val sleutel = try {
            Base64.getDecoder().decode(tekst)
        } catch (e: IllegalArgumentException) {
            throw AfgewezenException(Afwijzing.AES_SLEUTEL_ONGELDIG, e)
        }

        // 256 bit, zoals A256GCM vraagt; de 16 bytes uit 4.3.4.1 spreken 3.4 en stap 13 tegen.
        if (sleutel.size != AES_BYTES) throw AfgewezenException(Afwijzing.AES_SLEUTEL_ONGELDIG)

        return sleutel
    }

    private fun selfSignedSleutel(certificaat: String): ECPublicKey {
        val jws = Jose.leesJws(certificaat)
        val ongeverifieerd = Jose.jsonObject(jws.unverifiedPayload, Afwijzing.APPSLEUTEL_ONGELDIG)
        val appSleutel = CredentialControle.appSleutel(ongeverifieerd)
        val claims = Jose.verifieer(jws, appSleutel, Afwijzing.HANDTEKENING_ONGELDIG)

        if (claims["sub"] != CERTIFICAAT_SELF_SIGNED) throw AfgewezenException(Afwijzing.HANDTEKENING_ONGELDIG)

        return appSleutel
    }

    private fun controleerNonceHandtekening(compact: String, appSleutel: ECPublicKey, nonce: String) {
        val claims = Jose.verifieer(Jose.leesJws(compact), appSleutel, Afwijzing.NONCEHANDTEKENING_ONGELDIG)
        val klopt = claims["sub"] == "challenge_response" && claims["aud"] == oin && claims["nonce"] == nonce

        if (!klopt) throw AfgewezenException(Afwijzing.NONCEHANDTEKENING_ONGELDIG)
    }

    private fun geefUit(aesSleutel: ByteArray, identiteit: Identiteit, geldig: Duration, nu: Instant): UitgegevenSessie {
        val sessie = Sessie(oin, sessietoken(), aesSleutel, identiteit, nu.plus(geldig))

        sessies.bewaar(sessie)

        val antwoord = JsonUtil.toJson(mapOf("session_token" to sessie.token, "expires_in" to geldig.seconds))

        return UitgegevenSessie(sessie, Jose.versleutelDir(antwoord, aesSleutel))
    }

    private fun sessietoken(): String = buildString(TOKEN_LENGTE) {
        repeat(TOKEN_LENGTE) { append(TOKEN_TEKENS[willekeur.nextInt(TOKEN_TEKENS.length)]) }
    }

    companion object {
        const val CERTIFICAAT_APP_MANAGER = "app_manager_jwt_certificate"
        const val CERTIFICAAT_SELF_SIGNED = "certificate_type_self_signed"

        /** Hoe lang een nonce bruikbaar is; de standaard noemt geen termijn. */
        val NONCE_GELDIG: Duration = Duration.ofSeconds(60)

        /** Hoe lang een sessie ten hoogste duurt; niet te verlengen. */
        val SESSIE_GELDIG: Duration = Duration.ofMinutes(15)

        private const val NONCE_BYTES = 16
        private const val AES_BYTES = 32

        // 32 tekens uit 62 is ruim 190 bit; de standaard eist ten minste 22 tekens (4.3.4.2, stap 26).
        private const val TOKEN_LENGTE = 32
        private const val TOKEN_TEKENS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
