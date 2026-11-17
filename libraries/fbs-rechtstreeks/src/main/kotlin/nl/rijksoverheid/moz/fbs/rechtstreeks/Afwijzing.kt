package nl.rijksoverheid.moz.fbs.rechtstreeks

import java.time.Duration

/**
 * Welke controle een verzoek afwees. Alleen voor de eigen log van de Verifier: naar de app gaat
 * per soort exception één antwoord, zodat de app niet kan afleiden welke controle faalde. Een
 * [AfgewezenException] wordt `401`, een [JweOngeldigException] `400`.
 */
enum class Afwijzing {
    NONCE_ONBEKEND,
    PRESENTATIE_ONLEESBAAR,
    PRESENTATIE_ONVOLLEDIG,
    TIJDELIJKE_SLEUTEL_ONGELDIG,
    AES_SLEUTEL_ONGELDIG,
    CERTIFICAATSOORT_ONJUIST,
    HEADER_NIET_TOEGESTAAN,
    ALGORITME_ONJUIST,
    TYP_ONJUIST,
    APP_MANAGER_ONBEKEND,
    HANDTEKENING_ONGELDIG,
    CREDENTIAL_ONVOLLEDIG,
    APP_MANAGER_SLEUTEL_VERLOPEN,
    CREDENTIAL_NIET_GELDIG,
    SCOPE_ONJUIST,
    ONTVANGER_ONGELDIG,
    APPSLEUTEL_ONGELDIG,
    NONCEHANDTEKENING_ONGELDIG,
    SESSIE_ONBEKEND,
    VERSLEUTELING_ONGELDIG,
}

/**
 * Een verzoek is afgewezen; [reden] zegt welke controle faalde. Een oorzaak zet alleen de library
 * zelf, via [afgewezen], zodat er nooit een ruwe melding met invoer in een stacktrace belandt.
 */
class AfgewezenException internal constructor(val reden: Afwijzing, cause: Throwable?) :
    RuntimeException(reden.name, cause) {

    constructor(reden: Afwijzing) : this(reden, null)
}

/**
 * Een afwijzing die van [oorzaak] alleen het type bewaart. De melding van een JOSE-library kan een
 * stuk van de ontvangen invoer bevatten, zoals een ontvanger, en die hoort niet in een stacktrace.
 */
internal fun afgewezen(reden: Afwijzing, oorzaak: Throwable): AfgewezenException =
    AfgewezenException(reden, IllegalStateException(oorzaak.javaClass.name))

/**
 * Een JWE die zonder sleutel al als onjuist te herkennen is: geen compacte JWE, of een header met
 * een ander `alg` of `enc`, zonder `typ` `JWE`, of met een veld dat niet mag. De aanroeper antwoordt
 * `400`. Dat verraadt niets over de controles daarna: wie de JWE maakte, kan de header zelf lezen.
 * Net als bij [AfgewezenException] bewaart een oorzaak alleen haar type.
 */
class JweOngeldigException internal constructor(val reden: Afwijzing, cause: Throwable?) :
    RuntimeException(reden.name, cause) {

    internal constructor(reden: Afwijzing) : this(reden, null)
}

internal fun jweOngeldig(reden: Afwijzing, oorzaak: Throwable): JweOngeldigException =
    JweOngeldigException(reden, IllegalStateException(oorzaak.javaClass.name))

/** De opslag van challenges of sessies is vol; de aanroeper antwoordt `503` met `Retry-After`. */
class OpslagVolException(val opnieuwNa: Duration) :
    RuntimeException("opslag vol, opnieuw na ${opnieuwNa.seconds} s")
