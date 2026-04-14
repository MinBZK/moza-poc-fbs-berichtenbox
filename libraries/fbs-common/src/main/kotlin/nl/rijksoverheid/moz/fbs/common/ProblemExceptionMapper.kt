package nl.rijksoverheid.moz.fbs.common

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

@Provider
class ProblemExceptionMapper : ExceptionMapper<WebApplicationException> {

    private val log = Logger.getLogger(ProblemExceptionMapper::class.java)

    override fun toResponse(exception: WebApplicationException): Response {
        val status = exception.response?.status ?: 500

        if (status >= 500) {
            log.errorf(exception, "Server error %d: %s", status, exception.message)
        }

        val problem = Problem(
            title = Response.Status.fromStatusCode(status)?.reasonPhrase ?: "Error",
            status = status,
            detail = exception.message,
        )

        return Response.status(status)
            .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
            .entity(problem)
            .build()
    }
}
