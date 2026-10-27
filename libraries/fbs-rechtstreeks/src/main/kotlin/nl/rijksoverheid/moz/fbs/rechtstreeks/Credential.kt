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
 * Controle van het Verifiable Credential van een App Manager (4.3.1.1), aangescherpt volgens het
 * ontwerp: zie de constanten hieronder, de toets van de App Manager-sleutel op `iat` (4.6.2) en
 * [ontvanger].
 */
internal class CredentialControle(
    private val appManagers: AppManagerSleutels,
    private val scope: String,
) {

    fun controleer(compact: String, nu: Instant): Credential {
        val jws = Jose.leesJws(compact, Afwijzing.CREDENTIAL_ONVOLLEDIG)
        val ongeverifieerd = Jose.jsonObject(jws.unverifiedPayload, Afwijzing.CREDENTIAL_ONVOLLEDIG)
        val iss = ongeverifieerd["iss"] as? String ?: throw AfgewezenException(Afwijzing.CREDENTIAL_ONVOLLEDIG)
        val appManager = appManagers.zoek(iss, jws.keyIdHeaderValue) ?: throw AfgewezenException(Afwijzing.APP_MANAGER_ONBEKEND)
        val claims = Jose.verifieer(jws, appManager.publicKey, Afwijzing.HANDTEKENING_ONGELDIG)

        val iat = tijdstip(claims, "iat")
        val nbf = tijdstip(claims, "nbf")
        val exp = tijdstip(claims, "exp")

        if (!iat.isBefore(appManager.geldigTot)) throw AfgewezenException(Afwijzing.APP_MANAGER_SLEUTEL_VERLOPEN)

        // Uit `nbf` niet vóór `iat` en `nbf` niet in de toekomst volgt dat ook `iat` niet in de toekomst ligt.
        val geldig = !nbf.isBefore(iat) &&
            !nbf.isAfter(nu.plus(MARGE)) &&
            !exp.isAfter(iat.plus(MAXIMALE_LOOPTIJD)) &&
            !exp.isBefore(nu.plus(MINIMAAL_RESTEREND))

        if (!geldig) throw AfgewezenException(Afwijzing.CREDENTIAL_NIET_GELDIG)

        if (claims["scope"] != scope) throw AfgewezenException(Afwijzing.SCOPE_ONJUIST)

        return Credential(ontvanger(claims), appSleutel(claims), exp)
    }

    private fun ontvanger(claims: Map<String, Any>): Identificatienummer {
        val waarde = claims["ontvanger"] as? String ?: throw AfgewezenException(Afwijzing.CREDENTIAL_ONVOLLEDIG)

        val ontvanger = try {
            Identificatienummer.fromHeader(waarde)
        } catch (e: DomainValidationException) {
            throw afgewezen(Afwijzing.ONTVANGER_ONGELDIG, e)
        }

        // Een OIN hoort bij een organisatie die via FSC aansluit, niet bij de gebruiker van een app.
        if (ontvanger.type == IdentificatienummerType.OIN) throw AfgewezenException(Afwijzing.ONTVANGER_ONGELDIG)

        return ontvanger
    }

    /** Een tijdstip als geheel aantal seconden; buiten 1970–9999 is het geen bruikbaar moment. */
    private fun tijdstip(claims: Map<String, Any>, naam: String): Instant {
        val waarde = claims[naam] as? Long ?: throw AfgewezenException(Afwijzing.CREDENTIAL_ONVOLLEDIG)

        if (waarde !in 0..LAATSTE_SECONDE) throw AfgewezenException(Afwijzing.CREDENTIAL_NIET_GELDIG)

        return Instant.ofEpochSecond(waarde)
    }

    companion object {

        /** Marge voor klokverschil met de App Manager, gelijk aan die van het stelseldocument. */
        val MARGE: Duration = Duration.ofSeconds(60)

        /** Een credential met `exp − iat` boven deze grens wordt geweigerd: er is geen intrekking, dus de looptijd is de enige rem. */
        val MAXIMALE_LOOPTIJD: Duration = Duration.ofHours(24)

        /** Een credential dat binnen deze tijd verloopt, levert geen sessie meer op: de app ververst het eerst. */
        val MINIMAAL_RESTEREND: Duration = Duration.ofSeconds(60)

        /** 9999-12-31T23:59:59Z. */
        private const val LAATSTE_SECONDE = 253_402_300_799L

        /** Leest `cnf.jwk` als publieke P-256-sleutel; gedeeld met het self-signed certificaat. */
        internal fun appSleutel(claims: Map<String, Any>): ECPublicKey {
            val cnf = claims["cnf"] as? Map<*, *> ?: throw AfgewezenException(Afwijzing.APPSLEUTEL_ONGELDIG)
            val jwk = cnf["jwk"] as? Map<*, *> ?: throw AfgewezenException(Afwijzing.APPSLEUTEL_ONGELDIG)

            return Jose.ecSleutel(jwk, Afwijzing.APPSLEUTEL_ONGELDIG)
        }
    }
}
