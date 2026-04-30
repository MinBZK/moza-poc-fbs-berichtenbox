package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.common.exception.Problem
import nl.rijksoverheid.moz.fbs.common.exception.ProblemMediaType
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException
import org.jboss.logging.Logger
import java.net.URI
import java.util.UUID

/**
 * Mapt [CircuitBreakerOpenException] naar 503 Problem JSON conform OpenAPI-spec.
 * Zonder deze mapper valt de exception door naar 500 en is het contract verbroken.
 */
@Provider
class CircuitBreakerOpenExceptionMapper : ExceptionMapper<CircuitBreakerOpenException> {

    private val log = Logger.getLogger(CircuitBreakerOpenExceptionMapper::class.java)

    override fun toResponse(exception: CircuitBreakerOpenException): Response {
        val errorId = UUID.randomUUID()
        // Log op warn (niet error): de root-cause is al gelogd toen het circuit opende;
        // elke daaropvolgende request zou anders een duplicate error + stacktrace spammen.
        log.warnf("Circuit breaker open (errorId=%s): %s", errorId, exception.message)

        val problem = Problem(
            title = "Service Unavailable",
            status = 503,
            detail = "Magazijn tijdelijk niet beschikbaar. Probeer het later opnieuw.",
            instance = URI.create("urn:uuid:$errorId"),
        )

        return Response.status(503)
            .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
            .entity(problem)
            .build()
    }
}
