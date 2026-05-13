package nl.rijksoverheid.moz.fbs.common.exception

import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

/**
 * Mapt Bean Validation [jakarta.validation.ConstraintViolationException] (uit `@Valid`/`@NotNull`/`@Pattern`
 * op gegenereerde API-interfaces) naar 400 Problem JSON. Detail formatteert elke
 * schending als `paramName: message`, gescheiden door `;`, zodat de client weet welk
 * veld ongeldig was zonder dat interne paths gelekt worden.
 *
 * **Sanering**: zowel `propertyPath`-segment als `it.message` gaan door
 * `sanitizeClientDetail` voordat ze in `detail` belanden. Bean Validation-messages
 * komen uit `messages.properties`-bundles maar `@Pattern(message="…")` of custom
 * validators kunnen user-input echoen (bv. `@Pattern(regexp=…, message="waarde
 * '\${validatedValue}' ongeldig")`). Saneer voorkomt CRLF/file-pad-leak in detail
 * en cap't lengte op 500 chars (DoS-mitigatie bij N violations met lange messages).
 */
@Provider
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {

    private val log = Logger.getLogger(ConstraintViolationExceptionMapper::class.java)

    override fun toResponse(exception: ConstraintViolationException): Response {
        log.debugf("Validatiefout: %s", exception.constraintViolations)

        val rawDetail = exception.constraintViolations.joinToString("; ") {
            val paramName = it.propertyPath.lastOrNull()?.name ?: it.propertyPath.toString()
            "$paramName: ${it.message}"
        }

        return problemResponse(
            status = 400,
            title = "Bad Request",
            detail = sanitizeClientDetail(rawDetail),
        )
    }
}
