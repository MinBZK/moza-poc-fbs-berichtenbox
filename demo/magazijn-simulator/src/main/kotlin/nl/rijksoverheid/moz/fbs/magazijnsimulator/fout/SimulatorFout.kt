package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response

/**
 * Een [WebApplicationException] die zijn [Foutcode] meedraagt naar [ProblemExceptionMapper].
 *
 * Zonder deze drager kent de mapper alleen de statuscode, en die is te grof: `404` dekt zowel
 * "dit bericht bestaat niet" als "dit pad bestaat niet". Het alternatief — de throw-site zijn
 * eigen `Problem`-respons laten bouwen — werkt ook (de mapper laat die ongemoeid), maar dan
 * verliest het antwoord de logregel en het correlatie-id die de mapper eraan hangt.
 *
 * Alleen gebruiken waar de situatie een eigen kenmerk verdient; een gewone
 * `WebApplicationException` valt terug op [Foutcode.voorStatus].
 */
class SimulatorFout(
    val foutcode: Foutcode,
    status: Response.Status,
    detail: String,
) : WebApplicationException(detail, status)
