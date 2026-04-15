package nl.rijksoverheid.moz.fbs.common

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import java.net.URI
import java.util.UUID

@Provider
class ProblemExceptionMapper : ExceptionMapper<WebApplicationException> {

    private val log = Logger.getLogger(ProblemExceptionMapper::class.java)

    override fun toResponse(exception: WebApplicationException): Response {
        val status = exception.response?.status ?: 500
        val title = Response.Status.fromStatusCode(status)?.reasonPhrase ?: "Error"

        val problem = if (status >= 500) {
            // Log met correlation-id zodat support de echte oorzaak kan terugvinden,
            // maar expose de interne exception-message niet aan de client.
            val errorId = UUID.randomUUID()
            log.errorf(exception, "Server error %d (errorId=%s): %s", status, errorId, exception.message)
            Problem(
                title = title,
                status = status,
                detail = "Er is een interne fout opgetreden. Vermeld errorId bij contact met support.",
                instance = URI.create("urn:uuid:$errorId"),
            )
        } else {
            log.infof("Client error %d: %s", status, exception.message)
            Problem(
                title = title,
                status = status,
                detail = exception.message,
            )
        }

        return Response.status(status)
            .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
            .entity(problem)
            .build()
    }
}
