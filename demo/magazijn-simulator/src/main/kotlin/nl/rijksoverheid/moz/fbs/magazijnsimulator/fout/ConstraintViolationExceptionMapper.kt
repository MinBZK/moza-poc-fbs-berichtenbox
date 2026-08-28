package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * Mapt Bean Validation-schendingen op de gegenereerde interfaces (`@NotNull`, `@Pattern`, `@Size`)
 * naar de 400 `problem+json` die de spec voorschrijft. Zonder deze mapper antwoordt Quarkus met een
 * eigen violation-rapport in `application/json`, en dan is de simulator op zijn foutpad wél van een
 * echt magazijn te onderscheiden — precies wat hij niet mag zijn.
 *
 * `detail` noemt per schending het parameter-naampje en de melding. Het aantal schendingen is
 * begrensd zodat een request met honderden ongeldige velden geen even grote tussenstring bouwt; de
 * lengte van het eindresultaat begrenst [problemResponse] zelf.
 *
 * Bewust géén gevalideerde wáárde in de melding: `X-Ontvanger` kan een BSN dragen, en dat hoort
 * nooit in een antwoord of een log terecht te komen. De meldingen van Bean Validation noemen alleen
 * de regel, niet de invoer.
 */
@Provider
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {

    override fun toResponse(exception: ConstraintViolationException): Response = problemResponse(
        status = Response.Status.BAD_REQUEST.statusCode,
        title = "Bad Request",
        detail = exception.constraintViolations
            .asSequence()
            .take(MAX_SCHENDINGEN)
            .joinToString("; ") { schending ->
                val naam = schending.propertyPath.lastOrNull()?.name ?: schending.propertyPath.toString()

                "$naam: ${schending.message}"
            },
    )

    private companion object {
        const val MAX_SCHENDINGEN = 50
    }
}
