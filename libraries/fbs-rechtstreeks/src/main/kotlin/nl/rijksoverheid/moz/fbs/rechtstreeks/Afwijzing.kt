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

/** Wat er aan een JWE niet klopt dat zonder sleutel al te zien is. Ook alleen voor de eigen log. */
enum class JweFout {
    /** Geen compacte JWE, of een header die geen JSON-object is. */
    ONLEESBAAR,

    /** Langer dan een echte presentatie of een echt verzoek ooit wordt. */
    TE_GROOT,

    /** Een veld dat in deze JWE niet mag, of een waarde van het verkeerde type. */
    HEADER_ONGELDIG,

    /** Een ander `alg` of `enc` dan deze stap toestaat, of geen van beide. */
    ALGORITME_ONJUIST,

    /** Geen `typ` `JWE`. */
    TYP_ONJUIST,
}

/**
 * Alles wat de Verifier bij een verzoek van de app gooit. Sealed, zodat een aanroeper die de soorten
 * met `when` naar een status vertaalt, door de compiler op een nieuwe soort gewezen wordt. De
 * statuscode zelf hoort bij de aanroeper, niet bij deze library.
 */
sealed class VerifierFout(message: String, cause: Throwable?) : RuntimeException(message, cause)

/**
 * Een verzoek is afgewezen; [reden] zegt welke controle faalde. De aanroeper antwoordt `401`. Een
 * oorzaak zet alleen de library zelf, via [afgewezen], zodat er nooit een ruwe melding met invoer
 * in een stacktrace belandt.
 */
class AfgewezenException internal constructor(val reden: Afwijzing, cause: Throwable?) :
    VerifierFout("afgewezen: ${reden.name}", cause) {

    internal constructor(reden: Afwijzing) : this(reden, null)
}

/**
 * Een JWE die zonder sleutel al als onjuist te herkennen is; [reden] zegt wat. De aanroeper
 * antwoordt `400`. De Verifier controleert dit vóór hij de nonce of de sessie gebruikt, dus het
 * antwoord zegt niets over de nonce of over de controles daarna: wie de JWE maakte, kan de header
 * zelf lezen. Een `epk` die wel een object is maar geen geldige sleutel, valt hier niet onder: dat
 * is een poging op de sleutel van de Verifier, en die verbruikt de nonce als elke andere afwijzing.
 */
class JweOngeldigException internal constructor(val reden: JweFout, cause: Throwable?) :
    VerifierFout("jwe ongeldig: ${reden.name}", cause) {

    internal constructor(reden: JweFout) : this(reden, null)
}

/** De opslag van challenges of sessies is vol; de aanroeper antwoordt `503` met `Retry-After`. */
class OpslagVolException(val opnieuwNa: Duration) :
    VerifierFout("opslag vol, opnieuw na ${opnieuwNa.seconds} s", null)

/**
 * Van [oorzaak] alleen de typen in de keten. De melding van een JOSE-library kan een stuk van de
 * ontvangen invoer bevatten, zoals een ontvanger, en die hoort niet in een stacktrace. De typen
 * wel: jose4j verpakt bijna alles in een kale `JoseException`, en pas de keten onderscheidt een
 * tag die niet klopt van een IV die ontbreekt.
 */
private fun alleenTypen(oorzaak: Throwable): IllegalStateException =
    IllegalStateException(generateSequence(oorzaak) { it.cause }.take(MAX_KETEN).joinToString(" <- ") { it.javaClass.name })

private const val MAX_KETEN = 5

internal fun afgewezen(reden: Afwijzing, oorzaak: Throwable): AfgewezenException = AfgewezenException(reden, alleenTypen(oorzaak))

internal fun jweOngeldig(reden: JweFout, oorzaak: Throwable): JweOngeldigException = JweOngeldigException(reden, alleenTypen(oorzaak))
