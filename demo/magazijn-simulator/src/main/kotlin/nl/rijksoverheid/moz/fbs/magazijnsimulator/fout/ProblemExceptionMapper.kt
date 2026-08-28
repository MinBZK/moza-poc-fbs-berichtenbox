package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.Problem

/**
 * Zorgt dat elke [WebApplicationException] als `problem+json` bij de client aankomt.
 *
 * Draagt de exception al een Problem-response — zoals de resources die zelf opbouwen — dan gaat die
 * ongewijzigd door. Alleen voor exceptions die ergens anders vandaan komen (Quarkus zelf, een
 * bibliotheek) wordt er alsnog een Problem omheen gezet, zodat er geen HTML-foutpagina of lege body
 * naar buiten lekt.
 *
 * Deze mapper is bovendien nodig omdat [UncaughtExceptionMapper] op `Exception` staat: zonder een
 * specifiekere mapper voor [WebApplicationException] zou die het vangnet in werking stellen en zou
 * een bewuste 404 als 500 bij de client aankomen.
 */
@Provider
class ProblemExceptionMapper : ExceptionMapper<WebApplicationException> {

    override fun toResponse(exception: WebApplicationException): Response {
        val response = exception.response

        if (response.entity is Problem) return response

        val status = response.status
        val title = Response.Status.fromStatusCode(status)?.reasonPhrase ?: "Error"

        // Geen `detail`: de message van een exception die niet uit onze eigen code komt, kan
        // interne details dragen, en er is hier niets dat dat nog kan onderscheiden. De statusregel
        // zelf staat al in `title`.
        return problemResponse(status = status, title = title)
    }
}
