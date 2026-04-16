package nl.rijksoverheid.moz.fbs.common

import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

/**
 * Mapt Bean Validation [ConstraintViolationException] (uit `@Valid`/`@NotNull`/`@Pattern`
 * op gegenereerde API-interfaces) naar 400 Problem JSON. Detail formatteert elke
 * schending als `paramName: message`, gescheiden door `;`, zodat de client weet welk
 * veld ongeldig was zonder dat interne paths gelekt worden.
 */
@Provider
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {

    private val log = Logger.getLogger(ConstraintViolationExceptionMapper::class.java)

    override fun toResponse(exception: ConstraintViolationException): Response {
        log.debugf("Validatiefout: %s", exception.constraintViolations)

        val detail = exception.constraintViolations.joinToString("; ") {
            val paramName = it.propertyPath.lastOrNull()?.name ?: it.propertyPath.toString()
            "$paramName: ${it.message}"
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
}
