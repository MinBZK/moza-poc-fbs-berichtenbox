package nl.rijksoverheid.moz.berichtenlijst.berichten

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.berichtenlijst.api.model.Problem

@Provider
class ProblemExceptionMapper : ExceptionMapper<WebApplicationException> {

    override fun toResponse(exception: WebApplicationException): Response {
        val status = exception.response?.status ?: 500

        val problem = Problem()
        problem.status = status
        problem.title = Response.Status.fromStatusCode(status)?.reasonPhrase ?: "Error"
        problem.detail = exception.message

        return Response.status(status)
            .type(PROBLEM_JSON)
            .entity(problem)
            .build()
    }

    companion object {
        private val PROBLEM_JSON = MediaType.valueOf("application/problem+json")
    }
}
