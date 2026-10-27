package nl.rijksoverheid.moz.fbs.appmanager

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.rechtstreeks.AfgewezenException
import nl.rijksoverheid.moz.fbs.rechtstreeks.OpslagVolException
import org.jboss.logging.Logger

/** Elke afwijzing is voor de app dezelfde `401`, zodat hij niet kan afleiden welke controle faalde; de log krijgt de reden. */
@Provider
class AfgewezenMapper : ExceptionMapper<AfgewezenException> {

    private val log = Logger.getLogger(AfgewezenMapper::class.java)

    override fun toResponse(fout: AfgewezenException): Response {
        log.infof("Verzoek afgewezen: %s", fout.reden)

        return Response.status(Response.Status.UNAUTHORIZED)
            .type(PROBLEM)
            .header("WWW-Authenticate", "Magazijnsessie")
            .entity("""{"type":"urn:fbs:fout:geen-toegang","title":"Geen toegang","status":401}""")
            .build()
    }
}

/** Een volle opslag wijst op een te krap quotum of op iemand die hem vult; de operator hoort dat te zien. */
@Provider
class OpslagVolMapper : ExceptionMapper<OpslagVolException> {

    private val log = Logger.getLogger(OpslagVolMapper::class.java)

    override fun toResponse(fout: OpslagVolException): Response {
        log.warnf("Opslag vol; opnieuw over %d s", fout.opnieuwNa.seconds)

        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
            .type(PROBLEM)
            .header("Retry-After", fout.opnieuwNa.seconds.coerceAtLeast(1))
            .entity("""{"type":"urn:fbs:fout:tijdelijk-niet-beschikbaar","title":"Tijdelijk niet beschikbaar","status":503}""")
            .build()
    }
}

private val PROBLEM = MediaType("application", "problem+json")
