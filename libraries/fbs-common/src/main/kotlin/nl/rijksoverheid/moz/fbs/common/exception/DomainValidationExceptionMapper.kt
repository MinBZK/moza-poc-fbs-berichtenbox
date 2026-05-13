package nl.rijksoverheid.moz.fbs.common.exception

import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.common.FoutBeschrijving
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
        // Saneer log met `FoutBeschrijving.saneer` (redact ≥7-cijfer-PII +
        // strip CRLF, CWE-117 mitigatie). Domeinmessages zijn handgeschreven,
        // maar string-interpolatie van user-input kan alsnog control-chars
        // of BSN-achtige reeksen meedragen — saneer is goedkope verdediging.
        log.infof(
            "Domeinvalidatie geschonden: %s",
            FoutBeschrijving.saneer(exception.message),
        )
        // `detail` BEWUST ongesaneerd doorgegeven: handgeschreven domeinmessages
        // bevatten vaak API-pad-tokens (`/api/v1/berichten/{id}`) en validation-
        // pointers die `sanitizeClientDetail.FILE_PATH_PATTERN` als file-pad
        // zou stripen → client krijgt "URL  is ongeldig" zonder welk pad,
        // niet-actionable. `DomainValidationException`-call-sites gebruiken alleen
        // statische strings + getals-interpolatie (geen user-input string-
        // interpolatie), dus geen stack-frame-injection-vector. `ProblemException`-
        // pad blijft wel saneren — daar kan Jakarta defaults of upstream-message
        // door komen.
        return problemResponse(
            status = 400,
            title = "Bad Request",
            detail = exception.message,
        )
    }
}
