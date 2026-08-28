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
 * `detail` noemt per schending het parameter-naampje en de melding. Zowel het aantal schendingen
 * als de lengte van het geheel is begrensd: een request met veel ongeldige velden mag geen
 * onbegrensde response opleveren.
 */
@Provider
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {

    override fun toResponse(exception: ConstraintViolationException): Response {
        val detail = exception.constraintViolations
            .asSequence()
            .take(MAX_SCHENDINGEN)
            .joinToString("; ") { schending ->
                val naam = schending.propertyPath.lastOrNull()?.name ?: schending.propertyPath.toString()

                "$naam: ${schending.message}"
            }
            .take(MAX_DETAIL_LENGTE)

        return problemResponse(
            status = Response.Status.BAD_REQUEST.statusCode,
            title = "Bad Request",
            detail = detail,
        )
    }

    private companion object {
        const val MAX_SCHENDINGEN = 50
        const val MAX_DETAIL_LENGTE = 500
    }
}
