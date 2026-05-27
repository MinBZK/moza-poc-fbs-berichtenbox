package nl.rijksoverheid.moz.fbs.common.profiel

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.common.exception.Problem
import nl.rijksoverheid.moz.fbs.common.exception.ProblemMediaType
import org.jboss.logging.Logger

/**
 * Mapt [ToestemmingGeweigerdException] naar 403 Problem JSON. Het feit dát
 * een aanvraag geweigerd is, is geen lek: de aanleverende organisatie wist
 * al dat ze geprobeerd hebben te leveren. De waarde van de ontvanger blijft
 * uit het response-body — die wordt niet door deze exception gedragen.
 *
 * `Problem.detail` is gebaseerd op de [ToestemmingGeweigerdException.Reden]-enum
 * en bevat geen call-site-injecties; de specifieke afzender-OIN gaat alleen via
 * de applicatielog (niet via response-body) zodat AVG-risico minimaal blijft.
 */
@Provider
class ToestemmingGeweigerdExceptionMapper : ExceptionMapper<ToestemmingGeweigerdException> {

    private val log = Logger.getLogger(ToestemmingGeweigerdExceptionMapper::class.java)

    override fun toResponse(exception: ToestemmingGeweigerdException): Response {
        // info-niveau: dit is geen serverfout maar een normaal policy-besluit.
        log.infof("Toestemming geweigerd (reden=%s)", exception.reden)

        val problem = Problem(
            title = "Forbidden",
            status = 403,
            detail = when (exception.reden) {
                ToestemmingGeweigerdException.Reden.GEEN_PROFIEL ->
                    "Ontvanger heeft geen profiel bij MOZA."
                ToestemmingGeweigerdException.Reden.GEEN_ACTIEVE_VOORKEUR ->
                    "Ontvanger heeft geen actieve berichtenbox-voorkeur voor deze afzender."
            },
        )

        return Response.status(403)
            .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
            .entity(problem)
            .build()
    }
}
