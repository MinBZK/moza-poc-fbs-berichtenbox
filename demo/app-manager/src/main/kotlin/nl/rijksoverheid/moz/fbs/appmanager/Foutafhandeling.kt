package nl.rijksoverheid.moz.fbs.appmanager

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import nl.rijksoverheid.moz.fbs.rechtstreeks.AfgewezenException
import nl.rijksoverheid.moz.fbs.rechtstreeks.JweOngeldigException
import nl.rijksoverheid.moz.fbs.rechtstreeks.OpslagVolException
import nl.rijksoverheid.moz.fbs.rechtstreeks.VerifierFout
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.server.ServerExceptionMapper
import java.util.UUID

/** Een controle van de App Manager zelf faalde; [reden] is alleen voor de log. Wordt `401`, zoals elke afwijzing. */
class ActivatieAfgewezen(val reden: Activatiefout) : RuntimeException("afgewezen: ${reden.name}")

/** Welke eigen controle van de App Manager een verzoek afwees. */
enum class Activatiefout {
    SESSIE_ONBEKEND,

    /** Een `session_token` dat al eens is omgewisseld: een gelekte of opnieuw afgespeelde URL. */
    SESSIE_TOKEN_HERGEBRUIKT,
    KOPPELING_ONBEKEND,
    REGISTRATIE_ONBEKEND,
    VERZOEK_ONGELDIG,
    ANDERE_SLEUTEL,

    /** De koppeling of de registratie verdween tussen keuze en bevestiging. */
    KEUZE_NIET_BEVESTIGD,

    /** Een keuze die niet van de eigen keuzepagina komt. */
    ANDERE_ORIGIN,
}

/**
 * De envelop van een verzoek klopt niet (geen JSON-object, een veld ontbreekt of heeft het verkeerde
 * type). Wordt `400`, net als een JWE die zonder sleutel al niet klopt: de app kan dat zelf zien.
 */
class EnvelopOngeldig : RuntimeException("envelop ongeldig")

/**
 * De eigen sleutel is (bijna) verlopen: een challenge of credential zou door iedereen geweigerd
 * worden. Wordt `503`, met een alert-token in de log, want alleen de operator kan het oplossen.
 */
class SleutelVerlopen : RuntimeException("de sleutel van de App Manager is verlopen")

/**
 * Elke fout naar één antwoord per soort, met het pad (zonder query) in de log. `/register_app` en
 * de keuze daarna zijn browsernavigaties en krijgen een HTML-pagina; de protocolpaden
 * problem+json. Een onverwachte fout krijgt een correlatie-id; de log noemt nooit de query, want
 * daar staat bij `/register_app` het `session_token` in.
 */
class Foutvertaling {

    private val log = Logger.getLogger(Foutvertaling::class.java)

    @ServerExceptionMapper
    fun verifierFout(fout: VerifierFout, uri: UriInfo): Response = vertaal(fout, pad(uri))

    /** De `when` heeft geen `else`: komt er in de library een soort bij, dan wijst de compiler deze plek aan. */
    internal fun vertaal(fout: VerifierFout, pad: String): Response = when (fout) {
        is AfgewezenException -> {
            log.infof("Afgewezen op %s: %s", pad, fout.reden)

            geenToegang(pad)
        }

        is JweOngeldigException -> {
            log.infof("Ongeldige JWE op %s: %s", pad, fout.reden)

            ongeldig(pad)
        }

        // Een volle opslag wijst op een te krap quotum of op iemand die hem vult; de operator hoort dat te zien.
        is OpslagVolException -> {
            log.warnf("Opslag vol op %s; opnieuw over %d s", pad, fout.opnieuwNa.seconds)

            tijdelijkNietBeschikbaar(pad, fout.opnieuwNa.seconds.coerceAtLeast(1))
        }
    }

    @ServerExceptionMapper
    fun activatieAfgewezen(fout: ActivatieAfgewezen, uri: UriInfo): Response {
        val pad = pad(uri)

        if (fout.reden in VERDACHT) {
            log.warnf("Afgewezen op %s: %s", pad, fout.reden)
        } else {
            log.infof("Afgewezen op %s: %s", pad, fout.reden)
        }

        return geenToegang(pad)
    }

    // Het type van de parameter kiest welke exception deze methode afhandelt; de waarde zelf zegt niets.
    @Suppress("UnusedParameter")
    @ServerExceptionMapper
    fun envelopOngeldig(fout: EnvelopOngeldig, uri: UriInfo): Response {
        val pad = pad(uri)

        log.infof("Ongeldige envelop op %s", pad)

        return ongeldig(pad)
    }

    // Het type van de parameter kiest welke exception deze methode afhandelt; de waarde zelf zegt niets.
    @Suppress("UnusedParameter")
    @ServerExceptionMapper
    fun sleutelVerlopen(fout: SleutelVerlopen, uri: UriInfo): Response = sleutelVerlopenAntwoord(pad(uri))

    internal fun sleutelVerlopenAntwoord(pad: String): Response {
        log.errorf("APP_MANAGER_SLEUTEL_VERLOPEN op %s: zet een nieuwe sleutel en app-manager.sleutel-geldig-tot", pad)

        return tijdelijkNietBeschikbaar(pad, OPNIEUW_NA_SLEUTEL)
    }

    /** 404, 405, 406, 415 en dergelijke van het framework zelf: dezelfde vorm als de eigen fouten. */
    @ServerExceptionMapper
    fun framework(fout: WebApplicationException, uri: UriInfo): Response {
        val status = fout.response.status

        if (status >= Response.Status.INTERNAL_SERVER_ERROR.statusCode) return onverwacht(fout, uri)

        val pad = pad(uri)
        val titel = Response.Status.fromStatusCode(status)?.reasonPhrase ?: "Fout"

        log.debugf("%d op %s", status, pad)

        return Response.status(status)
            .type(PROBLEM)
            .apply { fout.response.getHeaderString(HttpHeaders.ALLOW)?.let { header(HttpHeaders.ALLOW, it) } }
            .entity("""{"type":"about:blank","title":"$titel","status":$status}""")
            .build()
    }

    @ServerExceptionMapper
    fun onverwacht(fout: Throwable, uri: UriInfo): Response {
        val pad = pad(uri)
        val correlatie = UUID.randomUUID()

        log.errorf(fout, "Onverwachte fout op %s (correlatie %s)", pad, correlatie)

        if (isBrowserpad(pad)) {
            return html(Response.Status.INTERNAL_SERVER_ERROR, "Er ging iets mis", "Probeer het later opnieuw. Kenmerk: $correlatie.")
        }

        return Response.serverError()
            .type(PROBLEM)
            .entity("""{"type":"urn:fbs:fout:interne-fout","title":"Interne fout","status":500,"instance":"urn:uuid:$correlatie"}""")
            .build()
    }

    private fun geenToegang(pad: String): Response =
        antwoord(pad, Response.Status.UNAUTHORIZED, "Activeren verlopen", "Het activeren is verlopen of al afgerond. Start opnieuw vanuit de app.")
            .entityProblem("""{"type":"urn:fbs:fout:geen-toegang","title":"Geen toegang","status":401}""", pad)
            // Het token gaat zonder schema mee (4.4.1, stap 20); dit is de naam van wat ontbreekt.
            .header(HttpHeaders.WWW_AUTHENTICATE, "Activatiesessie")
            .build()

    private fun ongeldig(pad: String): Response =
        antwoord(pad, Response.Status.BAD_REQUEST, "Ongeldige keuze", "Deze keuze kan niet. Ga terug en kies opnieuw.")
            .entityProblem("""{"type":"urn:fbs:fout:ongeldig-verzoek","title":"Ongeldig verzoek","status":400}""", pad)
            .build()

    private fun tijdelijkNietBeschikbaar(pad: String, opnieuwNa: Long): Response =
        antwoord(pad, Response.Status.SERVICE_UNAVAILABLE, "Even te druk", "Het is even te druk. Probeer het over een minuut opnieuw.")
            .entityProblem("""{"type":"urn:fbs:fout:tijdelijk-niet-beschikbaar","title":"Tijdelijk niet beschikbaar","status":503}""", pad)
            .header(HttpHeaders.RETRY_AFTER, opnieuwNa)
            .build()

    /** Een HTML-pagina op een browserpad; anders een lege builder die [entityProblem] vult. */
    private fun antwoord(pad: String, status: Response.Status, titel: String, tekst: String): Response.ResponseBuilder {
        if (!isBrowserpad(pad)) return Response.status(status)

        return Response.status(status)
            .type(MediaType.TEXT_HTML_TYPE.withCharset("utf-8"))
            .header("Content-Security-Policy", Keuzepagina.CSP_ZONDER_FORMULIER)
            .entity(Keuzepagina.foutpagina(titel, tekst))
    }

    private fun Response.ResponseBuilder.entityProblem(json: String, pad: String): Response.ResponseBuilder =
        if (isBrowserpad(pad)) this else type(PROBLEM).entity(json)

    private fun html(status: Response.Status, titel: String, tekst: String): Response =
        antwoord(ActiverenResource.REGISTER_APP, status, titel, tekst).build()

    private companion object {
        val PROBLEM = MediaType("application", "problem+json")
        val VERDACHT = setOf(Activatiefout.SESSIE_TOKEN_HERGEBRUIKT, Activatiefout.ANDERE_ORIGIN)
        const val OPNIEUW_NA_SLEUTEL = 300L

        /** Het pad zonder query; [UriInfo.getPath] laat die al weg. */
        fun pad(uri: UriInfo): String = "/" + uri.path.trimStart('/')

        fun isBrowserpad(pad: String): Boolean = pad == ActiverenResource.REGISTER_APP || pad.startsWith(ActiverenResource.REGISTER_APP + "/")
    }
}
