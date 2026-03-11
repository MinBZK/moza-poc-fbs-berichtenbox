package nl.rijksoverheid.moz.berichtenlijst.berichten

import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.berichtenlijst.api.model.Problem

@Provider
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {

    override fun toResponse(exception: ConstraintViolationException): Response {
        val problem = Problem()
        problem.status = 400
        problem.title = "Bad Request"
        problem.detail = exception.constraintViolations.joinToString("; ") {
            "${it.propertyPath}: ${it.message}"
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
