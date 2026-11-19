package nl.rijksoverheid.moz.fbs.kern.exception

import jakarta.ws.rs.NotAcceptableException
import jakarta.ws.rs.NotAllowedException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.NotSupportedException
import jakarta.ws.rs.WebApplicationException

/**
 * Wat er in `detail` staat bij een foutantwoord dat het framework zelf veroorzaakt: een pad dat niet
 * bestaat, een parameter die niet te lezen is, een methode die daar niet mag, een mediatype dat niet
 * te leveren of niet te lezen is.
 *
 * Het framework geeft zelf een melding mee, en die beschrijft zijn eigen werking ("Unable to find
 * matching target resource method", "did not match the value in @Produces"). Volgens de API Design
 * Rules hoort zo'n technisch detail niet in een foutantwoord, en `detail` is verder overal
 * Nederlands. Een afnemer heeft wel iets aan uitleg. Daarom krijgt zo'n antwoord een vaste zin in
 * plaats van een leeg veld.
 *
 * De keuze hangt aan het type van de exception en niet aan de status. Deze vier types gooit het
 * framework zelf; eigen code gebruikt een eigen exception met een kenmerk. Een fout met dezelfde
 * status die van een andere dienst komt, zoals een 415 van het magazijn die de uitvraag doorgeeft,
 * zegt niets over het verzoek van de afnemer en krijgt deze tekst dus ook niet.
 *
 * Deze regel staat hier en niet per dienst. Het echte magazijn en de simulator moeten op dit
 * foutpad hetzelfde antwoorden, en de simulator kan `fbs-common` niet gebruiken.
 */
object FrameworkfoutDetail {

    const val PAD_ONBEKEND = "Dit pad bestaat niet."
    const val PARAMETER_ONGELDIG = "Een pad- of queryparameter heeft geen geldige waarde."
    const val METHODE_NIET_TOEGESTAAN = "Deze HTTP-methode is op dit pad niet toegestaan."
    const val ACCEPT_NIET_TE_LEVEREN = "Geen van de mediatypes uit de Accept-header kan worden geleverd."
    const val MEDIATYPE_NIET_ONDERSTEUND = "Het mediatype van de request-body wordt op dit pad niet ondersteund."

    /** Wat het framework invult als er geen echte melding is, zoals "HTTP 400 Bad Request". */
    private val STANDAARDMELDING = Regex("""HTTP \d{3}( [A-Za-z][A-Za-z' -]*)?""")

    /**
     * De vaste tekst voor een exception van het framework, of `null` voor elke andere exception.
     *
     * Een 404 met een oorzaak is een parameter die niet te converteren is, zoals `?page=abc` of een
     * bericht-id dat geen UUID is. Jakarta REST schrijft daar een 404 voor, maar het pad bestaat wel.
     */
    fun voor(exception: WebApplicationException): String? = when (exception) {
        is NotFoundException -> if (exception.cause == null) PAD_ONBEKEND else PARAMETER_ONGELDIG
        is NotAllowedException -> METHODE_NIET_TOEGESTAAN
        is NotAcceptableException -> ACCEPT_NIET_TE_LEVEREN
        is NotSupportedException -> MEDIATYPE_NIET_ONDERSTEUND
        else -> null
    }

    /**
     * Of [melding] de standaardmelding van het framework is. Die voegt niets toe aan `title` en
     * `status`; beter geen `detail` dan die.
     */
    fun isStandaardmelding(melding: String): Boolean = STANDAARDMELDING.matches(melding.trim())
}
