package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.Foutcode
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.problemResponse
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnContext
import nl.rijksoverheid.moz.fbs.rechtstreeks.AfgewezenException
import nl.rijksoverheid.moz.fbs.rechtstreeks.OpslagVolException
import org.jboss.logging.Logger

/**
 * Elke afwijzing is voor de app dezelfde `401`, zonder detail over welke controle faalde; alleen
 * de log krijgt de reden. `WWW-Authenticate` is verplicht bij een `401` en draagt de OIN als realm,
 * zodat de app weet bij welk magazijn hij opnieuw moet beginnen.
 */
@Provider
class AfgewezenMapper(private val context: MagazijnContext) : ExceptionMapper<AfgewezenException> {

    private val log = Logger.getLogger(AfgewezenMapper::class.java)

    override fun toResponse(fout: AfgewezenException): Response {
        val oin = context.magazijnOfNiets?.oin

        log.infof("Rechtstreeks verzoek bij magazijn %s afgewezen: %s", oin, fout.reden)

        return Response.fromResponse(problemResponse(Response.Status.UNAUTHORIZED.statusCode, "Unauthorized", foutcode = Foutcode.GEEN_TOEGANG))
            .header("WWW-Authenticate", "Magazijnsessie realm=\"$oin\"")
            .build()
    }
}

/** Een volle opslag wijst op een te krap quotum of op iemand die hem vult; de operator hoort dat te zien. */
@Provider
class OpslagVolMapper(private val context: MagazijnContext) : ExceptionMapper<OpslagVolException> {

    private val log = Logger.getLogger(OpslagVolMapper::class.java)

    override fun toResponse(fout: OpslagVolException): Response {
        val seconden = fout.opnieuwNa.seconds.coerceAtLeast(1)

        log.warnf("Opslag voor rechtstreeks ophalen vol bij magazijn %s; opnieuw over %d s", context.magazijnOfNiets?.oin, seconden)

        return Response.fromResponse(
            problemResponse(Response.Status.SERVICE_UNAVAILABLE.statusCode, "Service Unavailable", foutcode = Foutcode.TIJDELIJK_NIET_BESCHIKBAAR),
        )
            .header("Retry-After", seconden)
            .build()
    }
}
