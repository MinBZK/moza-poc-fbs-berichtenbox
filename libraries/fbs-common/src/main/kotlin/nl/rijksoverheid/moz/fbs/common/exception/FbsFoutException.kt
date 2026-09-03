package nl.rijksoverheid.moz.fbs.common.exception

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response

/**
 * Een [WebApplicationException] die zijn [Foutcode] meedraagt naar [ProblemExceptionMapper].
 *
 * Zonder deze drager kent de mapper alleen de statuscode, en die is te grof: `404` dekt zowel
 * "dit bericht is niet van jou" als "dit pad bestaat niet". De code hier zetten houdt de
 * maskering, sanering en correlatie-id in de mapper — een throw-site die zijn eigen `Response`
 * bouwt, omzeilt die alle drie.
 *
 * Gebruik dit alleen waar de situatie een eigen kenmerk verdient. Een gewone
 * `WebApplicationException` blijft prima: de mapper valt dan terug op [Foutcode.voorStatus].
 */
class FbsFoutException : WebApplicationException {

    val foutcode: Foutcode

    constructor(
        foutcode: Foutcode,
        status: Response.Status,
        detail: String,
        cause: Throwable? = null,
    ) : super(detail, cause, status) {
        this.foutcode = foutcode
    }

    /**
     * Variant voor een fout die méér dan een status draagt — een `Retry-After` bijvoorbeeld.
     * De mapper bouwt zelf een verse response, maar leest zulke headers van deze respons af.
     */
    constructor(
        foutcode: Foutcode,
        response: Response,
        detail: String,
        cause: Throwable? = null,
    ) : super(detail, cause, response) {
        this.foutcode = foutcode
    }
}
