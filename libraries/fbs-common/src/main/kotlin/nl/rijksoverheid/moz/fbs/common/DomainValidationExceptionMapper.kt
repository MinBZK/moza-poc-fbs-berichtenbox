package nl.rijksoverheid.moz.fbs.common

import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

/**
 * Mapt [DomainValidationException] naar 400 met de door het domein zelf
 * geformuleerde boodschap — deze berichten zijn handgeschreven en veilig te delen.
 * Moet een hogere priority hebben dan [IllegalArgumentExceptionMapper] zodat JAX-RS
 * deze specifiekere mapper kiest.
 */
@Provider
@Priority(Priorities.USER - 100)
class DomainValidationExceptionMapper : ExceptionMapper<DomainValidationException> {

    private val log = Logger.getLogger(DomainValidationExceptionMapper::class.java)

    override fun toResponse(exception: DomainValidationException): Response {
        log.infof("Domeinvalidatie geschonden: %s", exception.message)

        val problem = Problem(
            title = "Bad Request",
            status = 400,
            detail = exception.message,
        )

        return Response.status(400)
            .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
            .entity(problem)
            .build()
    }
}
