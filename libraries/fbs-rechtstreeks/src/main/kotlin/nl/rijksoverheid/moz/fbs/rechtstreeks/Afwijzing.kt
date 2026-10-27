package nl.rijksoverheid.moz.fbs.rechtstreeks

import java.time.Duration

/**
 * Welke controle een verzoek afwees. Alleen voor de eigen log van de Verifier: naar de app gaat
 * voor elke reden hetzelfde antwoord (`401`), zodat de app niet kan afleiden welke controle faalde.
 */
enum class Afwijzing {
    NONCE_ONBEKEND,
    PRESENTATIE_ONLEESBAAR,
    PRESENTATIE_ONVOLLEDIG,
    AES_SLEUTEL_ONGELDIG,
    CERTIFICAATSOORT_ONJUIST,
    HEADER_NIET_TOEGESTAAN,
    UITGEVER_ONBEKEND,
    HANDTEKENING_ONGELDIG,
    CREDENTIAL_ONVOLLEDIG,
    UITGEVERSLEUTEL_VERLOPEN,
    CREDENTIAL_NIET_GELDIG,
    SCOPE_ONJUIST,
    ONTVANGER_ONGELDIG,
    APPSLEUTEL_ONGELDIG,
    NONCEHANDTEKENING_ONGELDIG,
    SESSIE_ONBEKEND,
    VERSLEUTELING_ONGELDIG,
}

/**
 * Een verzoek is afgewezen. Log [reden], niet de exception zelf: de oorzaak kan uit een
 * JOSE-library komen, en die neemt soms een stuk van de ontvangen inhoud op in haar melding.
 */
class AfgewezenException(val reden: Afwijzing, cause: Throwable? = null) :
    RuntimeException(reden.name, cause)

/** De opslag van challenges of sessies is vol; de aanroeper antwoordt `503` met `Retry-After`. */
class OpslagVolException(val opnieuwNa: Duration) :
    RuntimeException("opslag vol, opnieuw na ${opnieuwNa.seconds} s")
