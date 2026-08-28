package nl.rijksoverheid.moz.fbs.magazijnsimulator.fout

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.Problem
import org.jboss.logging.Logger
import java.util.UUID

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
 * een bewuste 404 als 500 bij de client aankomen. Keerzijde: een 5xx die als
 * `WebApplicationException` binnenkomt, bereikt dat vangnet dan óók niet — vandaar dat die tak hier
 * zelf logt. Een 500 zonder logregel is in een demo met honderd magazijnen niet te herleiden.
 */
@Provider
class ProblemExceptionMapper : ExceptionMapper<WebApplicationException> {

    private val log = Logger.getLogger(ProblemExceptionMapper::class.java)

    override fun toResponse(exception: WebApplicationException): Response {
        val response = exception.response

        if (response.entity is Problem) return response

        val status = response.status
        val title = Response.Status.fromStatusCode(status)?.reasonPhrase ?: "Error"
        val foutId = UUID.randomUUID()

        if (status >= SERVERFOUT_VANAF) {
            // De melding blijft uit de log: bij een exception van elders kan die gebruikersinvoer
            // dragen. Het exception-object levert de stack, en het correlatie-id koppelt log en
            // antwoord aan elkaar.
            log.errorf(exception, "Serverfout %d (foutId=%s, type=%s)", status, foutId, exception.javaClass.name)
        }

        // Geen `detail`: de message van een exception die niet uit onze eigen code komt, kan interne
        // details dragen, en er is hier niets dat dat nog kan onderscheiden. De statusregel zelf
        // staat al in `title`.
        return problemResponse(status = status, title = title, foutId = foutId)
    }

    private companion object {
        const val SERVERFOUT_VANAF = 500
    }
}
