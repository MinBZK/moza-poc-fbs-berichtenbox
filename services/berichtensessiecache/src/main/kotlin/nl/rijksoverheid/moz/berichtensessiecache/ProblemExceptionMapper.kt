package nl.rijksoverheid.moz.berichtensessiecache

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.berichtensessiecache.api.model.Problem
import org.jboss.logging.Logger
import java.net.URI

@Provider
class ProblemExceptionMapper : ExceptionMapper<WebApplicationException> {

    private val log = Logger.getLogger(ProblemExceptionMapper::class.java)

    override fun toResponse(exception: WebApplicationException): Response {
        val status = exception.response?.status ?: 500

        if (status >= 500) {
            log.errorf(exception, "Server error %d: %s", status, exception.message)
        }

        val problem = Problem()
        problem.type = URI.create("about:blank")
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
