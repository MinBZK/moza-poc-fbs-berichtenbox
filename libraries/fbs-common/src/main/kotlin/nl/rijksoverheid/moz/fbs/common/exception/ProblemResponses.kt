package nl.rijksoverheid.moz.fbs.common.exception

import jakarta.ws.rs.core.Response
import java.net.URI
import java.util.UUID

/**
 * Bouwt een RFC 9457 Problem-response: status, content-type en entity in één regel.
 * Vervangt het 4-regels-patroon dat in elke ExceptionMapper terugkwam.
 */
internal fun problemResponse(
    status: Int,
    title: String,
    detail: String?,
    instance: URI? = null,
): Response {
    val problem = Problem(title = title, status = status, detail = detail, instance = instance)
    return Response.status(status)
        .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
        .entity(problem)
        .build()
}

/**
 * Standaard 5xx Problem met gemaskeerd detail en correlation-id (`urn:uuid:<errorId>`).
 * De client ziet geen interne details; support kan via errorId in de applicatielog zoeken.
 *
 * De aanroeper is verantwoordelijk voor het loggen van de exception met dezelfde errorId —
 * de exact gewenste log-boodschap verschilt per mapper en hoort daar te blijven.
 */
internal fun maskedServerErrorProblem(
    errorId: UUID,
    status: Int = 500,
    title: String = "Internal Server Error",
    detail: String = "Er is een onverwachte interne fout opgetreden. Vermeld errorId bij contact met support.",
): Response = problemResponse(
    status = status,
    title = title,
    detail = detail,
    instance = URI.create("urn:uuid:$errorId"),
)
