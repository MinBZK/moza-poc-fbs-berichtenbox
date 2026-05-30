package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger

/**
 * Uniforme upstream-fout-mapping voor calls naar sessiecache én magazijn.
 *
 * 4xx propageert 1-op-1 (cliënt-/contract-fout, bv. 404 cache-miss); een
 * transport-storing of een 5xx (upstream down/kapot) wordt 502 Bad Gateway.
 * Zo geldt overal in de uitvraag-service "502 = upstream-fout, niet onze fout",
 * en wordt on-call niet de uitvraag-service in gestuurd bij een storing die in
 * de sessiecache of het magazijn zit. Toegepast op alle lees- én schrijf-paden
 * zodat de classificatie consistent is (zie [BerichtOphaalService],
 * [BerichtenlijstService], [BerichtBeheerService]).
 */
internal inline fun <T> mapUpstreamFout(log: Logger, context: String, block: () -> T): T =
    try {
        block()
    } catch (e: WebApplicationException) {
        if (!isUpstreamTransportFout(e)) throw e

        // isUpstreamTransportFout liet door: óf geen response (transport-fout vóór
        // HTTP-antwoord) óf een echte 5xx. Log eerlijk welke van de twee, anders zet
        // "upstream 5xx → 502" een debugger op het verkeerde been bij een timeout.
        if (e.response == null) {
            log.errorf(e, "%s: upstream zonder response (transport-fout) → 502", context)
        } else {
            log.errorf(e, "%s: upstream 5xx → 502", context)
        }

        throw upstreamBadGateway(context)
    } catch (e: ProcessingException) {
        log.errorf(e, "%s: upstream transport-fout → 502", context)
        throw upstreamBadGateway(context)
    }

/**
 * Geen response = de call brak af vóór een HTTP-antwoord van de upstream → we
 * behandelen dat als transport-fout. Anders: alleen 5xx telt als transport/down.
 */
internal fun isUpstreamTransportFout(e: WebApplicationException): Boolean {
    val status = e.response?.status ?: return true

    return status >= 500
}

// Jakarta REST 3.1 kent geen BadGatewayException; een expliciete WAE met
// 502-status geeft downstream dezelfde semantiek.
internal fun upstreamBadGateway(detail: String): WebApplicationException =
    WebApplicationException(detail, Response.Status.BAD_GATEWAY)
