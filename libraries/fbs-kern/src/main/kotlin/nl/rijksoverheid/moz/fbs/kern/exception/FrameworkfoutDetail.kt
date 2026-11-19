package nl.rijksoverheid.moz.fbs.kern.exception

import jakarta.ws.rs.NotAcceptableException
import jakarta.ws.rs.NotAllowedException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.NotSupportedException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response

/**
 * Wat er in `detail` staat bij een foutantwoord dat het framework zelf veroorzaakt: een pad dat niet
 * bestaat, een parameter die niet te lezen is, een methode die daar niet mag, een mediatype
 * dat niet te leveren of niet te lezen is.
 *
 * Het framework geeft zelf een melding mee, en die beschrijft zijn eigen werking ("Unable to find
 * matching target resource method", "did not match the value in @Produces"). Volgens de API Design
 * Rules hoort zo'n technisch detail niet in een foutantwoord. Een afnemer heeft wel iets aan uitleg.
 * Daarom krijgt zo'n antwoord een vaste zin in plaats van een leeg veld.
 *
 * De keuze hangt aan het type van de exception en niet aan de status. Eigen code gooit deze types
 * niet, maar een `WebApplicationException` of een eigen subklasse daarvan. Een fout met dezelfde
 * status die van een andere dienst komt, zoals een 415 die de uitvraag van het magazijn doorgeeft,
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
    const val MEDIATYPE_NIET_ONDERSTEUND = "Het mediatype uit de Content-Type-header wordt op dit pad niet ondersteund."

    private val STANDAARDMELDING = Regex("""HTTP (\d{3})(?: (.+))?""")

    /** De vaste tekst voor een exception van het framework, of `null` voor elke andere exception. */
    fun voor(exception: WebApplicationException): String? = when (exception) {
        is NotFoundException -> if (isOngeldigeParameter(exception)) PARAMETER_ONGELDIG else PAD_ONBEKEND
        is NotAllowedException -> METHODE_NIET_TOEGESTAAN
        is NotAcceptableException -> ACCEPT_NIET_TE_LEVEREN
        is NotSupportedException -> MEDIATYPE_NIET_ONDERSTEUND
        else -> null
    }

    /**
     * Of [exception] een pad-, query- of matrixparameter is die niet te converteren is, zoals
     * `?page=abc` of een bericht-id dat geen UUID is. Jakarta REST schrijft daarvoor een 404 voor,
     * met de conversiefout als oorzaak. Maar het pad bestaat wel: de aanroeper stuurde een ongeldige
     * waarde, en de spec declareert daarvoor een 400. Een mapper geeft hem daarom die status.
     */
    fun isOngeldigeParameter(exception: WebApplicationException): Boolean =
        exception is NotFoundException && exception.cause != null

    /**
     * Of [melding] de standaardmelding van het framework is, zoals "HTTP 400 Bad Request": precies
     * de status en zijn reason phrase. Die voegt niets toe aan `title` en `status`; beter geen
     * `detail` dan die. Een eigen melding die zo begint ("HTTP 409 Bericht is al gelezen") blijft.
     */
    fun isStandaardmelding(melding: String): Boolean {
        val treffer = STANDAARDMELDING.matchEntire(melding.trim()) ?: return false
        val reden = treffer.groupValues[2]

        return reden.isEmpty() || reden == Response.Status.fromStatusCode(treffer.groupValues[1].toInt())?.reasonPhrase
    }
}
