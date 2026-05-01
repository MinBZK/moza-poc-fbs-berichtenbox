package nl.rijksoverheid.moz.fbs.common.exception

import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

/**
 * Mapt [DomainValidationException] naar 400 met de door het domein zelf
 * geformuleerde boodschap — deze berichten zijn handgeschreven en veilig te delen.
 * `@Priority(USER - 100)` is een tiebreaker; JAX-RS kiest deze mapper sowieso al
 * via type-specificiteit boven [UncaughtExceptionMapper] (`<Exception>`).
 */
@Provider
@Priority(Priorities.USER - 100)
class DomainValidationExceptionMapper : ExceptionMapper<DomainValidationException> {

    private val log = Logger.getLogger(DomainValidationExceptionMapper::class.java)

    override fun toResponse(exception: DomainValidationException): Response {
        log.infof("Domeinvalidatie geschonden: %s", exception.message)
        return problemResponse(status = 400, title = "Bad Request", detail = exception.message)
    }
}
