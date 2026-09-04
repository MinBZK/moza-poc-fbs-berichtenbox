package nl.rijksoverheid.moz.fbs.common.exception

import jakarta.ws.rs.core.Response
import java.net.URI
import java.util.UUID

/**
 * Bouwt een RFC 9457 Problem-response: status, content-type en entity in één regel.
 * Vervangt het 4-regels-patroon dat in elke ExceptionMapper terugkwam.
 *
 * [foutcode] wordt het `type` van het antwoord. Zonder parameter-default, want een weggelaten
 * kenmerk laat het antwoord terugvallen op `about:blank` — precies wat een afnemer niet kan
 * onderscheiden van een fout die onderweg is verzonnen.
 */
internal fun problemResponse(
    status: Int,
    title: String,
    detail: String?,
    foutcode: Foutcode,
    instance: URI? = null,
): Response {
    // Via `of` en niet via de constructor: die clamp-t een status buiten 400..599 naar 500. Een
    // aanroeper kan een `WebApplicationException` met een 2xx-status bouwen — Jakarta weigert dat
    // niet — en zonder deze clamp zou daar een `200 OK` met een foutkenmerk uit komen.
    val problem = Problem.of(
        title = title,
        status = status,
        detail = detail,
        instance = instance,
        type = foutcode.uri,
    )
    return Response.status(problem.status)
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
    foutcode: Foutcode = Foutcode.INTERNE_FOUT,
): Response = problemResponse(
    status = status,
    title = title,
    detail = detail,
    foutcode = foutcode,
    instance = URI.create("urn:uuid:$errorId"),
)
