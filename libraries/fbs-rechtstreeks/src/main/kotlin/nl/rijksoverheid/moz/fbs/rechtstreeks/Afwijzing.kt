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
    TIJDELIJKE_SLEUTEL_ONGELDIG,
    AES_SLEUTEL_ONGELDIG,
    CERTIFICAATSOORT_ONJUIST,
    HEADER_NIET_TOEGESTAAN,
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
 * Een verzoek is afgewezen. Log [reden], niet de exception zelf: de oorzaak kan uit een
 * JOSE-library komen, en die neemt soms een stuk van de ontvangen inhoud op in haar melding.
 */
class AfgewezenException(val reden: Afwijzing, cause: Throwable? = null) :
    RuntimeException(reden.name, cause)

/**
 * Een afwijzing die van [oorzaak] alleen het type bewaart. De melding van een JOSE-library kan een
 * stuk van de ontvangen invoer bevatten, zoals een ontvanger, en die hoort niet in een stacktrace.
 */
internal fun afgewezen(reden: Afwijzing, oorzaak: Throwable): AfgewezenException =
    AfgewezenException(reden, IllegalStateException(oorzaak.javaClass.name))

/** De opslag van challenges of sessies is vol; de aanroeper antwoordt `503` met `Retry-After`. */
class OpslagVolException(val opnieuwNa: Duration) :
    RuntimeException("opslag vol, opnieuw na ${opnieuwNa.seconds} s")
