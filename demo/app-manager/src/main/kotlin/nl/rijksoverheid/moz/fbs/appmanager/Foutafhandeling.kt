package nl.rijksoverheid.moz.fbs.appmanager

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.rechtstreeks.AfgewezenException
import nl.rijksoverheid.moz.fbs.rechtstreeks.JweOngeldigException
import nl.rijksoverheid.moz.fbs.rechtstreeks.OpslagVolException
import nl.rijksoverheid.moz.fbs.rechtstreeks.VerifierFout
import org.jboss.logging.Logger

/** Een controle van de App Manager zelf faalde; [reden] is alleen voor de log. Wordt `401`, zoals elke afwijzing. */
class ActivatieAfgewezen(val reden: Activatiefout) : RuntimeException("afgewezen: ${reden.name}")

/** Welke eigen controle van de App Manager een verzoek afwees. */
enum class Activatiefout {
    SESSIE_ONBEKEND,
    KOPPELING_ONBEKEND,
    REGISTRATIE_ONBEKEND,
    VERZOEK_ONGELDIG,
    ANDERE_SLEUTEL,
}

/**
 * De envelop van een verzoek klopt niet (geen JSON-object, een veld ontbreekt of heeft het verkeerde
 * type). Wordt `400`, net als een JWE die zonder sleutel al niet klopt: de app kan dat zelf zien.
 */
class EnvelopOngeldig : RuntimeException("envelop ongeldig")

/**
 * Alles wat de Verifier gooit, naar één status per soort. De `when` heeft geen `else`: komt er in de
 * library een soort bij, dan wijst de compiler deze plek aan.
 */
@Provider
class VerifierFoutMapper : ExceptionMapper<VerifierFout> {

    private val log = Logger.getLogger(VerifierFoutMapper::class.java)

    override fun toResponse(fout: VerifierFout): Response = when (fout) {
        is AfgewezenException -> {
            log.infof("Verzoek afgewezen: %s", fout.reden)

            geenToegang()
        }

        is JweOngeldigException -> {
            log.infof("Ongeldige JWE: %s", fout.reden)

            ongeldigVerzoek()
        }

        // Een volle opslag wijst op een te krap quotum of op iemand die hem vult; de operator hoort dat te zien.
        is OpslagVolException -> {
            log.warnf("Opslag vol; opnieuw over %d s", fout.opnieuwNa.seconds)

            Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(PROBLEM)
                .header("Retry-After", fout.opnieuwNa.seconds.coerceAtLeast(1))
                .entity("""{"type":"urn:fbs:fout:tijdelijk-niet-beschikbaar","title":"Tijdelijk niet beschikbaar","status":503}""")
                .build()
        }
    }
}

/** Dezelfde `401` als een afwijzing door de Verifier, zodat de app niet kan afleiden welke controle faalde. */
@Provider
class ActivatieAfgewezenMapper : ExceptionMapper<ActivatieAfgewezen> {

    private val log = Logger.getLogger(ActivatieAfgewezenMapper::class.java)

    override fun toResponse(fout: ActivatieAfgewezen): Response {
        log.infof("Verzoek afgewezen: %s", fout.reden)

        return geenToegang()
    }
}

@Provider
class EnvelopOngeldigMapper : ExceptionMapper<EnvelopOngeldig> {

    override fun toResponse(fout: EnvelopOngeldig): Response = ongeldigVerzoek()
}

private fun geenToegang(): Response = Response.status(Response.Status.UNAUTHORIZED)
    .type(PROBLEM)
    .header("WWW-Authenticate", "Magazijnsessie")
    .entity("""{"type":"urn:fbs:fout:geen-toegang","title":"Geen toegang","status":401}""")
    .build()

private fun ongeldigVerzoek(): Response = Response.status(Response.Status.BAD_REQUEST)
    .type(PROBLEM)
    .entity("""{"type":"urn:fbs:fout:ongeldig-verzoek","title":"Ongeldig verzoek","status":400}""")
    .build()

private val PROBLEM = MediaType("application", "problem+json")
