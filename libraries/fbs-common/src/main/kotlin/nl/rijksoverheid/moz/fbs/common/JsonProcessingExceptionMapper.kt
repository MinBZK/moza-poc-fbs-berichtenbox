package nl.rijksoverheid.moz.fbs.common

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

private val log: Logger = Logger.getLogger("nl.rijksoverheid.moz.fbs.common.JsonProcessingExceptionMapper")

internal fun jsonProcessingExceptionToResponse(exception: JsonProcessingException): Response {
    // Jackson-boodschappen bevatten vaak class-/package-namen en input fragments. Nooit
    // verbatim terug — wel intern loggen voor debugging.
    log.infof(exception, "JSON-deserialisatiefout (detail niet naar client): %s", exception.originalMessage)

    val detail = when (exception) {
        is MismatchedInputException -> {
            val path = exception.path.joinToString(".") { it.fieldName ?: "[${it.index}]" }
            if (path.isNotBlank()) {
                "Ongeldige JSON-invoer voor veld '$path'."
            } else {
                "Ongeldige JSON-invoer."
            }
        }
        else -> "Ongeldige JSON-invoer."
    }

    val problem = Problem(
        title = "Bad Request",
        status = 400,
        detail = detail,
    )

    return Response.status(400)
        .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
        .entity(problem)
        .build()
}

/**
 * Mapt generieke Jackson [JsonProcessingException]s naar een 400 Problem JSON.
 * De interne Jackson-boodschap wordt niet naar de client gelekt.
 */
@Provider
@Priority(Priorities.USER)
class JsonProcessingExceptionMapper : ExceptionMapper<JsonProcessingException> {
    override fun toResponse(exception: JsonProcessingException): Response =
        jsonProcessingExceptionToResponse(exception)
}

/**
 * Mapt Jackson's [MismatchedInputException] (bv. ontbrekende verplichte velden)
 * naar een 400 Problem JSON response. Deze mapper is specifieker dan
 * [JsonProcessingExceptionMapper] en overstemt de ingebouwde
 * `BuiltinMismatchedInputExceptionMapper` van quarkus-rest-jackson.
 */
@Provider
@Priority(Priorities.USER)
class MismatchedInputExceptionMapper : ExceptionMapper<MismatchedInputException> {
    override fun toResponse(exception: MismatchedInputException): Response =
        jsonProcessingExceptionToResponse(exception)
}
