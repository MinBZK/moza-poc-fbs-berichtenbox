package nl.rijksoverheid.moz.fbs.common.exception

import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.kern.exception.Veldpad
import org.jboss.logging.Logger
import java.net.URI
import java.util.UUID

/**
 * Mapt Bean Validation [jakarta.validation.ConstraintViolationException] (uit `@Valid`/`@NotNull`/`@Pattern`
 * op gegenereerde API-interfaces) naar 400 Problem JSON. Detail formatteert elke
 * schending als `paramName: message`, gescheiden door `;`, zodat de client weet welk
 * veld ongeldig was zonder dat interne paths gelekt worden.
 *
 * **Sanering**: de samengestelde `paramName: message`-detailstring gaat in één keer
 * door `sanitizeClientDetail` voordat hij wordt geretourneerd. Bean Validation-messages
 * komen uit `messages.properties`-bundles maar `@Pattern(message="…")` of custom
 * validators kunnen user-input echoen (bv. `@Pattern(regexp=…, message="waarde
 * '\${validatedValue}' ongeldig")`). Saneer voorkomt CRLF/file-pad-leak in detail
 * en kapt de lengte af (zie `MAX_CLIENT_DETAIL_LENGTH` in `sanitizeClientDetail`),
 * naast de eigen [MAX_VIOLATIONS_IN_DETAIL]-grens op het aantal violations hieronder
 * — samen DoS-mitigatie bij N violations met lange messages.
 */
@Provider
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {

    private val log = Logger.getLogger(ConstraintViolationExceptionMapper::class.java)

    override fun toResponse(exception: ConstraintViolationException): Response {
        val errorId = UUID.randomUUID()

        // Per schending alleen het pad en de soort regel. Een schending draagt de aangeboden waarde
        // (`invalidValue`); een eigen melding met `${validatedValue}` en het pad van een map-sleutel
        // noemen haar ook. Een BSN in `X-Ontvanger` hoort op geen enkel logniveau in de log.
        log.infof(
            "Validatiefout (errorId=%s, aantal=%d, schendingen=%s)",
            errorId,
            exception.constraintViolations.size,
            exception.constraintViolations
                .asSequence()
                .take(MAX_VIOLATIONS_IN_DETAIL)
                .joinToString(", ") { schendingVoorLog(it) },
        )

        // .take(MAX_VIOLATIONS_IN_DETAIL) begrenst memory-pressure bij N=10000+
        // violations (groot request met veel @Pattern-velden) — sanitizeClientDetail
        // begrenst alleen het eindresultaat, niet de tussenstring-allocatie.
        val rawDetail = exception.constraintViolations
            .asSequence()
            .take(MAX_VIOLATIONS_IN_DETAIL)
            .joinToString("; ") {
                val paramName = it.propertyPath.lastOrNull()?.name ?: it.propertyPath.toString()
                "$paramName: ${it.message}"
            }

        // sanitizeClientDetail null-return is in deze format onmogelijk: paramName + ": "
        // is altijd minstens 2 chars die saneer overlaat. Geen fallback nodig.
        return problemResponse(
            status = 400,
            title = "Bad Request",
            detail = sanitizeClientDetail(rawDetail),
            foutcode = Foutcode.ONGELDIG_VERZOEK,
            instance = URI.create("urn:uuid:$errorId"),
        )
    }

    private fun schendingVoorLog(schending: ConstraintViolation<*>): String {
        val segmenten = buildList<Any?> {
            schending.propertyPath.forEach { knoop ->
                // Van een element in een collectie alleen de positie: een map-sleutel is invoer,
                // en wordt via `null` onleesbaar.
                if (knoop.isInIterable) add(knoop.index)

                knoop.name?.let(::add)
            }
        }

        val regel = schending.constraintDescriptor?.annotation?.annotationClass?.simpleName ?: "onbekend"

        return "${Veldpad.van(segmenten)} ($regel)"
    }

    companion object {
        /**
         * Bovengrens op aantal violations in `detail`-string. Bij meer violations
         * krijgt de client de eerste N — voldoende om actionable te zijn, beperkt
         * memory-pressure bij volume-aanvallen of grote requests met veel velden.
         */
        const val MAX_VIOLATIONS_IN_DETAIL = 50
    }
}
