package nl.rijksoverheid.moz.fbs.common.exception

import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.common.FoutBeschrijving
import org.jboss.logging.Logger
import java.net.URI
import java.util.UUID

/**
 * Mapt [DomainValidationException] naar 400 met de door het domein zelf
 * geformuleerde boodschap — deze berichten zijn handgeschreven en veilig te delen.
 * `@Priority(USER - 100)` is een tiebreaker; JAX-RS kiest deze mapper sowieso al
 * via type-specificiteit boven [UncaughtExceptionMapper] (`<Exception>`).
 *
 * **Call-site-invariant** (afgedwongen door [DomainValidationCallSiteContractTest]):
 * call-sites mogen GEEN user-input in de message interpoleren, want `detail` gaat
 * ongesaneerd naar de client (zie [toResponse]). Toegestaan: statische strings +
 * numerieke/length-interpolatie (`"$length"`, `"$count"`).
 */
@Provider
@Priority(Priorities.USER - 100)
class DomainValidationExceptionMapper : ExceptionMapper<DomainValidationException> {

    private val log = Logger.getLogger(DomainValidationExceptionMapper::class.java)

    override fun toResponse(exception: DomainValidationException): Response {
        // errorId correleert client (`urn:uuid:...` in Problem.instance) met de
        // applicatielog; consistent met de andere mapper-paden.
        val errorId = UUID.randomUUID()
        // Log via `FoutBeschrijving.saneer` (zie daar voor de garanties). Cause-message
        // en cause-type zijn extra correlatie-handvatten voor call-sites die context
        // meegeven zonder die in `Problem.detail` te lekken.
        log.infof(
            "Domeinvalidatie geschonden (errorId=%s, causeType=%s, cause=%s): %s",
            errorId,
            exception.cause?.javaClass?.simpleName ?: "geen",
            FoutBeschrijving.saneer(exception.cause?.message),
            FoutBeschrijving.saneer(exception.message),
        )
        // `detail` BEWUST ongesaneerd: domeinmessages bevatten vaak API-pad-tokens
        // (`/api/v1/berichten/{id}`) die `sanitizeClientDetail` als file-pad zou
        // stripen → niet-actionable client-fout. De call-site-invariant (zie KDoc)
        // houdt user-input uit de message, dus geen injection-vector.
        return problemResponse(
            status = 400,
            title = "Bad Request",
            detail = exception.message,
            instance = URI.create("urn:uuid:$errorId"),
        )
    }
}
