package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger

/**
 * Uniforme upstream-fout-mapping voor calls naar de sessiecache-facade én het magazijn.
 *
 * Allowlist: alleen een echte client-/contract-`4xx` (400..499) propageert 1-op-1
 * (bv. 404 cache-miss of 409 cache-nog-niet-gevuld); al het andere — geen response
 * (transport-storing) en elke non-4xx-status (1xx/3xx/5xx) — wordt 502. Rationale:
 * voor een server-naar-server client is een lekkende non-4xx geen begrijpelijk
 * contract, dus telt als upstream-storing. Zo blijft "502 = upstream-fout, niet onze
 * fout" overal gelden en wordt on-call bij een Redis-/magazijn-storing niet de
 * uitvraag-service in gestuurd. Toegepast op alle lees- én schrijf-paden (zie
 * [BerichtOphaalService], [BerichtenlijstService], [BerichtBeheerService]).
 */
internal inline fun <T> mapUpstreamFout(log: Logger, context: String, block: () -> T): T =
    try {
        block()
    } catch (e: WebApplicationException) {
        if (!isUpstreamStoring(e)) throw e

        // isUpstreamStoring liet door: óf geen response (transport-fout vóór
        // HTTP-antwoord) óf een non-4xx-status (3xx/5xx/onverwacht). Log eerlijk welke
        // van de twee mét de status, anders zet "upstream → 502" een debugger op het
        // verkeerde been bij een timeout vs. een onverwachte 3xx.
        if (e.response == null) {
            log.errorf(e, "%s: upstream zonder response (transport-fout) → 502", context)
        } else {
            log.errorf(e, "%s: upstream non-4xx (status=%d) → 502", context, e.response.status)
        }

        throw upstreamBadGateway(context)
    } catch (e: ProcessingException) {
        log.errorf(e, "%s: upstream transport-fout → 502", context)

        throw upstreamBadGateway(context)
    }

/**
 * Allowlist-predicaat: `true` = upstream-storing (→ 502), `false` = propageerbare
 * client-/contract-`4xx`. Geen response = transport-fout (call brak af vóór een
 * HTTP-antwoord). Een aanwezige non-4xx-status (1xx/3xx/5xx/onverwacht) telt óók
 * als storing — voor een server-naar-server-client is dat geen begrijpelijk
 * contract. Naam dekt bewust méér dan transport-fouten; zie de allowlist hierboven.
 */
internal fun isUpstreamStoring(e: WebApplicationException): Boolean {
    val status = e.response?.status ?: return true

    return status !in 400..499
}

// Jakarta REST 3.1 kent geen BadGatewayException; een expliciete WAE met
// 502-status geeft downstream dezelfde semantiek. Canonieke 502-helper voor de
// hele uitvraag-service. Geef waar beschikbaar de onderliggende fout als cause
// mee zodat exception-keten-gebaseerde logging de oorzaak niet verliest.
internal fun upstreamBadGateway(detail: String, cause: Throwable? = null): WebApplicationException =
    WebApplicationException(detail, cause, Response.Status.BAD_GATEWAY)
