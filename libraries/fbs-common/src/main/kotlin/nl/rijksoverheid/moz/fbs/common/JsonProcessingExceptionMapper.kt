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
    log.debugf("JSON-deserialisatiefout: %s", exception.originalMessage)

    val detail = when (exception) {
        is MismatchedInputException -> {
            val path = exception.path.joinToString(".") { it.fieldName ?: "[${it.index}]" }
            if (path.isNotBlank()) {
                "Ongeldige invoer voor veld '$path': ${exception.originalMessage}"
            } else {
                exception.originalMessage
            }
        }
        else -> exception.originalMessage
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
