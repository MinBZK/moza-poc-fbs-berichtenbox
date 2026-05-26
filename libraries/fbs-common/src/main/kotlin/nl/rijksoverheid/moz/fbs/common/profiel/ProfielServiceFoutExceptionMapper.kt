package nl.rijksoverheid.moz.fbs.common.profiel

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.common.exception.Problem
import nl.rijksoverheid.moz.fbs.common.exception.ProblemMediaType
import org.jboss.logging.Logger
import java.net.URI

/**
 * Vertaalt [ProfielServiceFoutException] naar HTTP 503 + Problem+JSON met
 * `Retry-After: 30`. Caller weet niet welke specifieke fault (5xx, timeout,
 * malformed) erachter zit — alleen dat de toestemmingscontrole nu niet
 * uitgevoerd kan worden. Retry-After geeft een afgesproken back-off-window.
 */
@Provider
class ProfielServiceFoutExceptionMapper : ExceptionMapper<ProfielServiceFoutException> {

    private val log = Logger.getLogger(ProfielServiceFoutExceptionMapper::class.java)

    override fun toResponse(exception: ProfielServiceFoutException): Response {
        // Bewust geen full stacktrace via .warnf(exception, …): de cause-chain van
        // lower-level HTTP-clients kan in randgevallen de upstream-URL bevatten,
        // die de BSN/RSIN/KVK in het pad heeft (extern Profiel-contract). Log alleen
        // de message + cause-type voor diagnose; correlation-id verbindt naar de
        // applicatielog elders waar de stacktrace correct gesaneerd wordt.
        log.warnf(
            "Profiel-service onbereikbaar: %s (cause=%s)",
            exception.message,
            exception.cause?.javaClass?.simpleName ?: "geen",
        )

        val problem = Problem(
            type = URI.create("https://moza.nl/problems/profiel-service-onbereikbaar"),
            title = "Profiel-service tijdelijk niet beschikbaar",
            status = 503,
            detail = "De toestemmingscontrole kon niet uitgevoerd worden. Probeer over 30 seconden opnieuw.",
        )

        return Response.status(503)
            .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
            .entity(problem)
            .header("Retry-After", "30")
            .build()
    }
}
