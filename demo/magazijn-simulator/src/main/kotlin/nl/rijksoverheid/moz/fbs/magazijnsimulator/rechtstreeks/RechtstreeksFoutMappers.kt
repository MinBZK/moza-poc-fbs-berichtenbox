package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.Foutcode
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.problemResponse
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnContext
import nl.rijksoverheid.moz.fbs.rechtstreeks.AfgewezenException
import nl.rijksoverheid.moz.fbs.rechtstreeks.Afwijzing
import nl.rijksoverheid.moz.fbs.rechtstreeks.JweOngeldigException
import nl.rijksoverheid.moz.fbs.rechtstreeks.OpslagVolException
import nl.rijksoverheid.moz.fbs.rechtstreeks.VerifierFout
import org.jboss.logging.Logger
import java.time.Duration
import java.util.UUID

/** De berichtpaden van de tweede ingang bestaan in het contract; zie `TODO(#1215)` in [SimulatorRechtstreeksResource]. */
class NogNietBeschikbaarException : RuntimeException("berichtpaden van de tweede ingang zijn niet beschikbaar")

/** Een berichtpad zonder precies één `Authorization: Bearer <token>`; voor de app dezelfde `401` als een onbekende sessie. */
class SessieOntbreektException : RuntimeException("geen Bearer-token")

/**
 * Alles wat de Verifier gooit, naar één antwoord per soort. Elke afwijzing is voor de app dezelfde
 * `401`, zonder detail over welke controle faalde; alleen de log krijgt de reden, met het
 * correlatie-id uit `instance`.
 */
@Provider
class VerifierFoutMapper(private val context: MagazijnContext) : ExceptionMapper<VerifierFout> {

    private val log = Logger.getLogger(VerifierFoutMapper::class.java)

    override fun toResponse(fout: VerifierFout): Response {
        val oin = context.magazijn.oin
        val foutId = UUID.randomUUID()

        return when (fout) {
            is AfgewezenException -> {
                log.infof("Rechtstreeks verzoek bij magazijn %s afgewezen (%s): %s %s", oin, foutId, fout.reden, fout.cause?.message.orEmpty())

                // Alleen bij die reden is er een token getoetst; bij het openen van een sessie gaat het om de presentatie.
                geenToegang(oin, foutId, tokenGetoetst = fout.reden == Afwijzing.SESSIE_ONBEKEND)
            }

            is JweOngeldigException -> {
                log.infof("Rechtstreeks verzoek bij magazijn %s met ongeldige JWE (%s): %s %s", oin, foutId, fout.reden, fout.cause?.message.orEmpty())

                problemResponse(BAD_REQUEST, "Bad Request", foutId = foutId, foutcode = Foutcode.ONGELDIG_VERZOEK)
            }

            // Een volle opslag wijst op een te krap quotum of op iemand die hem vult; de operator hoort dat te zien.
            is OpslagVolException -> {
                val seconden = naarBoven(fout.opnieuwNa)

                log.warnf("Opslag voor rechtstreeks ophalen vol bij magazijn %s (%s); opnieuw over %d s", oin, foutId, seconden)

                tijdelijkNietBeschikbaar(foutId, seconden)
            }
        }
    }
}

@Provider
class SessieOntbreektMapper(private val context: MagazijnContext) : ExceptionMapper<SessieOntbreektException> {

    private val log = Logger.getLogger(SessieOntbreektMapper::class.java)

    override fun toResponse(fout: SessieOntbreektException): Response {
        val foutId = UUID.randomUUID()

        log.infof("Rechtstreeks verzoek bij magazijn %s zonder Bearer-token (%s)", context.magazijn.oin, foutId)

        return geenToegang(context.magazijn.oin, foutId, tokenGetoetst = false)
    }
}

/** Geen storing, dus op info en zonder stacktrace: een app vraagt hier gewoon om. */
@Provider
class NogNietBeschikbaarMapper(private val context: MagazijnContext) : ExceptionMapper<NogNietBeschikbaarException> {

    private val log = Logger.getLogger(NogNietBeschikbaarMapper::class.java)

    override fun toResponse(fout: NogNietBeschikbaarException): Response {
        val foutId = UUID.randomUUID()

        log.infof("Rechtstreeks berichtenpad bij magazijn %s gevraagd (%s); nog niet beschikbaar", context.magazijn.oin, foutId)

        return tijdelijkNietBeschikbaar(foutId, NOG_NIET_OPNIEUW_NA)
    }

    private companion object {
        const val NOG_NIET_OPNIEUW_NA = 3600L
    }
}

/**
 * `WWW-Authenticate` is verplicht bij een `401` (RFC 9110) en draagt de OIN als realm, zodat de app
 * weet bij welk magazijn hij opnieuw begint. `error="invalid_token"` alleen als er een Bearer-token
 * getoetst en afgewezen is; zonder token, of bij een afgewezen presentatie, hoort er geen foutcode
 * in (RFC 6750, 3.1).
 */
private fun geenToegang(oin: String, foutId: UUID, tokenGetoetst: Boolean): Response {
    val wwwAuthenticate = if (tokenGetoetst) "Bearer realm=\"$oin\", error=\"invalid_token\"" else "Bearer realm=\"$oin\""

    return Response.fromResponse(problemResponse(UNAUTHORIZED, "Unauthorized", foutId = foutId, foutcode = Foutcode.GEEN_TOEGANG))
        .header("WWW-Authenticate", wwwAuthenticate)
        .build()
}

private fun tijdelijkNietBeschikbaar(foutId: UUID, seconden: Long): Response = Response.fromResponse(
    problemResponse(SERVICE_UNAVAILABLE, "Service Unavailable", foutId = foutId, foutcode = Foutcode.TIJDELIJK_NIET_BESCHIKBAAR),
)
    .header("Retry-After", seconden)
    .build()

/** Naar boven: een poging die een halve seconde te vroeg komt, krijgt weer een `503`. */
internal fun naarBoven(duur: Duration): Long = ((duur.toMillis() + MILLIS_PER_SECONDE - 1) / MILLIS_PER_SECONDE).coerceAtLeast(1)

internal const val AUTHORIZATION = "Authorization"

private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val SERVICE_UNAVAILABLE = 503
private const val MILLIS_PER_SECONDE = 1000L
