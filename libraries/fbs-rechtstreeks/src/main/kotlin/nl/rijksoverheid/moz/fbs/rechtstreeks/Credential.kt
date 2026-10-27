package nl.rijksoverheid.moz.fbs.rechtstreeks

import nl.rijksoverheid.moz.fbs.kern.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.kern.identificatie.IdentificatienummerType
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.time.Instant

/** Wat een gecontroleerd Verifiable Credential oplevert: wie, met welke sleutel, tot wanneer. */
internal class Credential(val ontvanger: Identificatienummer, val appSleutel: ECPublicKey, val exp: Instant)

/**
 * Controle van het Verifiable Credential van een App Manager (4.3.1.1), met de aanscherpingen uit
 * het ontwerp: een maximale looptijd, en de sleutel van de uitgever getoetst op `iat` (4.6.2).
 */
internal class CredentialControle(
    private val uitgevers: UitgeverSleutels,
    private val scope: String,
) {

    fun controleer(compact: String, nu: Instant): Credential {
        val jws = Jose.leesJws(compact)
        val ongeverifieerd = Jose.jsonObject(jws.unverifiedPayload, Afwijzing.CREDENTIAL_ONVOLLEDIG)
        val iss = ongeverifieerd["iss"] as? String ?: throw AfgewezenException(Afwijzing.CREDENTIAL_ONVOLLEDIG)
        val uitgever = uitgevers.zoek(iss, jws.keyIdHeaderValue) ?: throw AfgewezenException(Afwijzing.UITGEVER_ONBEKEND)
        val claims = Jose.verifieer(jws, uitgever.publicKey, Afwijzing.HANDTEKENING_ONGELDIG)

        val iat = tijdstip(claims, "iat")
        val nbf = tijdstip(claims, "nbf")
        val exp = tijdstip(claims, "exp")

        if (!iat.isBefore(uitgever.geldigTot)) throw AfgewezenException(Afwijzing.UITGEVERSLEUTEL_VERLOPEN)

        controleerTijden(iat, nbf, exp, nu)

        if (claims["scope"] != scope) throw AfgewezenException(Afwijzing.SCOPE_ONJUIST)

        return Credential(ontvanger(claims), appSleutel(claims), exp)
    }

    private fun controleerTijden(iat: Instant, nbf: Instant, exp: Instant, nu: Instant) {
        val geldig = !iat.isAfter(nu.plus(MARGE)) &&
            !nbf.isBefore(iat) &&
            !nbf.isAfter(nu.plus(MARGE)) &&
            !exp.isAfter(iat.plus(MAXIMALE_LOOPTIJD)) &&
            !exp.isBefore(nu.plus(MINIMAAL_RESTEREND))

        if (!geldig) throw AfgewezenException(Afwijzing.CREDENTIAL_NIET_GELDIG)
    }

    private fun ontvanger(claims: Map<String, Any>): Identificatienummer {
        val waarde = claims["ontvanger"] as? String ?: throw AfgewezenException(Afwijzing.CREDENTIAL_ONVOLLEDIG)

        val ontvanger = try {
            Identificatienummer.fromHeader(waarde)
        } catch (e: DomainValidationException) {
            throw AfgewezenException(Afwijzing.ONTVANGER_ONGELDIG, e)
        }

        // Een OIN hoort bij een organisatie die via FSC aansluit, niet bij de gebruiker van een app.
        if (ontvanger.type == IdentificatienummerType.OIN) throw AfgewezenException(Afwijzing.ONTVANGER_ONGELDIG)

        return ontvanger
    }

    private fun tijdstip(claims: Map<String, Any>, naam: String): Instant {
        val waarde = claims[naam] as? Long ?: throw AfgewezenException(Afwijzing.CREDENTIAL_ONVOLLEDIG)

        return Instant.ofEpochSecond(waarde)
    }

    companion object {

        /** Marge voor klokverschil, gelijk aan die van het stelseldocument. */
        val MARGE: Duration = Duration.ofSeconds(60)

        /** Langer geldig kan een App Manager een credential niet uitgeven; er is geen intrekking. */
        val MAXIMALE_LOOPTIJD: Duration = Duration.ofHours(24)

        /** Een credential dat binnen deze tijd verloopt, levert geen sessie meer op: de app ververst het eerst. */
        val MINIMAAL_RESTEREND: Duration = Duration.ofSeconds(60)

        /** Leest `cnf.jwk` als publieke P-256-sleutel; gedeeld met het self-signed certificaat. */
        internal fun appSleutel(claims: Map<String, Any>): ECPublicKey {
            val cnf = claims["cnf"] as? Map<*, *> ?: throw AfgewezenException(Afwijzing.APPSLEUTEL_ONGELDIG)
            val jwk = cnf["jwk"] as? Map<*, *> ?: throw AfgewezenException(Afwijzing.APPSLEUTEL_ONGELDIG)

            return Jose.ecSleutel(jwk.entries.associate { (k, v) -> k.toString() to v }, Afwijzing.APPSLEUTEL_ONGELDIG)
        }
    }
}
