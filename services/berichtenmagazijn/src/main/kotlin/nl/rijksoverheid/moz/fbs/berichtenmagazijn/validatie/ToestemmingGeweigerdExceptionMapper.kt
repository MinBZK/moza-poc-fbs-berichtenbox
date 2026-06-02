package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.common.exception.Problem
import nl.rijksoverheid.moz.fbs.common.exception.ProblemMediaType
import org.jboss.logging.Logger

/**
 * Mapt [ToestemmingGeweigerdException] naar 403 Problem JSON. Het feit dát
 * een aanvraag geweigerd is, is geen lek: de aanlevernde organisatie wist
 * al dat ze geprobeerd hebben te leveren. De waarde van de ontvanger blijft
 * uit het response-body — die wordt niet door deze exception gedragen.
 */
@Provider
class ToestemmingGeweigerdExceptionMapper : ExceptionMapper<ToestemmingGeweigerdException> {

    private val log = Logger.getLogger(ToestemmingGeweigerdExceptionMapper::class.java)

    override fun toResponse(exception: ToestemmingGeweigerdException): Response {
        // info-niveau: geen serverfout maar een normaal policy-besluit. Afzender-OIN
        // is een publiek organisatienummer (geen persoonsgegeven) en mag voluit in de
        // log; de ontvanger (mogelijk BSN) blijft eruit.
        log.infof(
            "Toestemming geweigerd (reden=%s, afzender=%s)",
            exception.reden,
            exception.afzender.waarde,
        )

        val problem = Problem(
            title = "Forbidden",
            status = 403,
            detail = exception.message,
        )

        return Response.status(403)
            .type(ProblemMediaType.APPLICATION_PROBLEM_JSON_TYPE)
            .entity(problem)
            .build()
    }
}

