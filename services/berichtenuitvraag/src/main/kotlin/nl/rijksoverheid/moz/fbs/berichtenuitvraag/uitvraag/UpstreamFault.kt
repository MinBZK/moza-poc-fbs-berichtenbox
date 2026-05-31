package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger

/**
 * Uniforme upstream-fout-mapping voor calls naar sessiecache én magazijn.
 *
 * Allowlist-classificatie: alleen een echte client-/contract-`4xx` (400..499)
 * propageert 1-op-1 (bv. 404 cache-miss). Een transport-storing (geen response)
 * én elke andere upstream-status — 3xx, 1xx, 5xx, alles buiten 400..499 — wordt
 * 502 Bad Gateway. "502 = niet onze fout; alleen echte client/contract-4xx
 * propageren we 1-op-1." Voor een server-naar-server REST-client is een naar de
 * client lekkende 3xx (of 1xx/onverwachte status) verkeerd: zulke statussen zijn
 * geen contract dat onze client kan begrijpen, dus behandelen we ze als een
 * upstream-storing. Zo geldt overal in de uitvraag-service "502 = upstream-fout,
 * niet onze fout", en wordt on-call niet de uitvraag-service in gestuurd bij een
 * storing die in de sessiecache of het magazijn zit. Toegepast op alle lees- én
 * schrijf-paden zodat de classificatie consistent is (zie [BerichtOphaalService],
 * [BerichtenlijstService], [BerichtBeheerService]).
 */
internal inline fun <T> mapUpstreamFout(log: Logger, context: String, block: () -> T): T =
    try {
        block()
    } catch (e: WebApplicationException) {
        if (!isUpstreamTransportFout(e)) throw e

        // isUpstreamTransportFout liet door: óf geen response (transport-fout vóór
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
 * Allowlist: alleen een echte client-/contract-`4xx` mag 1-op-1 propageren; al
 * het andere telt als upstream-storing → 502. Geen response = de call brak af
 * vóór een HTTP-antwoord (transport-fout). Een aanwezige non-4xx-status (3xx,
 * 1xx, 5xx, onverwacht) is voor onze server-naar-server-client geen begrijpelijk
 * contract en wordt daarom óók als upstream-storing behandeld.
 */
internal fun isUpstreamTransportFout(e: WebApplicationException): Boolean {
    val status = e.response?.status ?: return true

    return status !in 400..499
}

// Jakarta REST 3.1 kent geen BadGatewayException; een expliciete WAE met
// 502-status geeft downstream dezelfde semantiek.
internal fun upstreamBadGateway(detail: String): WebApplicationException =
    WebApplicationException(detail, Response.Status.BAD_GATEWAY)

/**
 * Classificeert een upstream-fout op de SSE-passthrough volgens dezelfde allowlist
 * als [mapUpstreamFout]: een echte 4xx behoudt zijn status, transport-fouten en
 * non-4xx worden 502.
 *
 * Let op: op de SSE-stream commit RESTEasy de 200-headers al bij subscriptie, vóór
 * een upstream-fout via Mutiny binnenkomt — de hier teruggegeven status bereikt de
 * client dus niet meer (de stream termineert onder de reeds verzonden 200). De
 * functie bepaalt daarmee de getypeerde terminal-fout (logging + nette Mutiny-
 * afhandeling), niet de wire-status. Los gehouden zodat de classificatie-invariant
 * unit-testbaar is zonder de niet-onderhandelbare SSE-status.
 */
internal fun ssePreStreamFout(e: Throwable): Throwable =
    if (e is WebApplicationException && !isUpstreamTransportFout(e)) e else upstreamBadGateway("SSE-passthrough upstream-fout")
