package nl.rijksoverheid.moz.berichtensessiecache

import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.berichtensessiecache.api.model.Problem
import org.jboss.logging.Logger
import java.net.URI

@Provider
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {

    private val log = Logger.getLogger(ConstraintViolationExceptionMapper::class.java)

    override fun toResponse(exception: ConstraintViolationException): Response {
        log.debugf("Validatiefout: %s", exception.constraintViolations)

        val problem = Problem()
        problem.type = URI.create("about:blank")
        problem.status = 400
        problem.title = "Bad Request"
        problem.detail = exception.constraintViolations.joinToString("; ") {
            val paramName = it.propertyPath.lastOrNull()?.name ?: it.propertyPath.toString()
            "$paramName: ${it.message}"
        }

        return Response.status(400)
            .type(PROBLEM_JSON)
            .entity(problem)
            .build()
    }

    companion object {
        private val PROBLEM_JSON = MediaType.valueOf("application/problem+json")
    }
}
