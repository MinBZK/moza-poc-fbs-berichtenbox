package nl.rijksoverheid.moz.fbs.common.exception

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response

/**
 * Een [WebApplicationException] die zijn [Foutcode] meedraagt naar [ProblemExceptionMapper].
 *
 * Zonder deze drager kent de mapper alleen de statuscode, en die is te grof: `404` dekt zowel
 * "dit bericht is niet van jou" als "dit pad bestaat niet". De rolverdeling blijft zoals hij was:
 * de throw-site kent de situatie, de mapper bouwt de respons — inclusief maskering, sanering en
 * correlatie-id. Wie zijn eigen respons bouwt, omzeilt die drie.
 *
 * [retryAfterSeconden] is daarom geen respons maar een hint: de mapper zet hem als `Retry-After`
 * op de 5xx die hij bouwt. Een volledige `Response` meegeven zou suggereren dat entity en overige
 * headers meegaan, en dat doen ze niet.
 *
 * Gebruik dit alleen waar de situatie een eigen kenmerk verdient. Een gewone
 * `WebApplicationException` blijft prima: de mapper valt dan terug op [Foutcode.voorStatus].
 */
class FbsFoutException(
    val foutcode: Foutcode,
    status: Response.Status,
    detail: String,
    cause: Throwable? = null,
    retryAfterSeconden: Int? = null,
) : WebApplicationException(detail, cause, responsMetHint(status, retryAfterSeconden))

private fun responsMetHint(status: Response.Status, retryAfterSeconden: Int?): Response {
    val bouwer = Response.status(status)

    if (retryAfterSeconden != null) bouwer.header("Retry-After", retryAfterSeconden)

    return bouwer.build()
}
