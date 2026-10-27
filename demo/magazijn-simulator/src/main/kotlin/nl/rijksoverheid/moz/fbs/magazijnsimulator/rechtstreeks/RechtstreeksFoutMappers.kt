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
import java.time.Duration
import java.util.UUID

/**
 * Elke afwijzing is voor de app dezelfde `401`, zonder detail over welke controle faalde; alleen
 * de log krijgt de reden, met het correlatie-id uit `instance`. `WWW-Authenticate` is verplicht bij
 * een `401` en draagt de OIN als realm, zodat de app weet bij welk magazijn hij opnieuw begint.
 */
@Provider
class AfgewezenMapper(private val context: MagazijnContext) : ExceptionMapper<AfgewezenException> {

    private val log = Logger.getLogger(AfgewezenMapper::class.java)

    override fun toResponse(fout: AfgewezenException): Response {
        val oin = context.magazijn.oin
        val foutId = UUID.randomUUID()

        // De oorzaak is in de library al teruggebracht tot een klassenaam: veilig om te loggen.
        log.infof("Rechtstreeks verzoek bij magazijn %s afgewezen (%s): %s %s", oin, foutId, fout.reden, fout.cause?.message.orEmpty())

        return Response.fromResponse(problemResponse(UNAUTHORIZED, "Unauthorized", foutId = foutId, foutcode = Foutcode.GEEN_TOEGANG))
            .header("WWW-Authenticate", "Magazijnsessie realm=\"$oin\"")
            .build()
    }

    private companion object {
        const val UNAUTHORIZED = 401
    }
}

/** Een volle opslag wijst op een te krap quotum of op iemand die hem vult; de operator hoort dat te zien. */
@Provider
class OpslagVolMapper(private val context: MagazijnContext) : ExceptionMapper<OpslagVolException> {

    private val log = Logger.getLogger(OpslagVolMapper::class.java)

    override fun toResponse(fout: OpslagVolException): Response {
        val foutId = UUID.randomUUID()
        val seconden = naarBoven(fout.opnieuwNa)

        log.warnf("Opslag voor rechtstreeks ophalen vol bij magazijn %s (%s); opnieuw over %d s", context.magazijn.oin, foutId, seconden)

        return tijdelijkNietBeschikbaar(foutId, seconden)
    }
}

private fun tijdelijkNietBeschikbaar(foutId: UUID, seconden: Long): Response = Response.fromResponse(
    problemResponse(SERVICE_UNAVAILABLE, "Service Unavailable", foutId = foutId, foutcode = Foutcode.TIJDELIJK_NIET_BESCHIKBAAR),
)
    .header("Retry-After", seconden)
    .build()

/** Naar boven: een poging die een halve seconde te vroeg komt, krijgt weer een `503`. */
internal fun naarBoven(duur: Duration): Long = ((duur.toMillis() + MILLIS_PER_SECONDE - 1) / MILLIS_PER_SECONDE).coerceAtLeast(1)

private const val SERVICE_UNAVAILABLE = 503
private const val MILLIS_PER_SECONDE = 1000L
