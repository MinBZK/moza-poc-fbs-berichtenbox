package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
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

/**
 * Enige plek waar de gesloten [SessiecacheException]-hiërarchie naar een HTTP-status
 * wordt vertaald. De `when` is exhaustief zónder `else`: een nieuw foutscenario in de
 * cache-library breekt hier de build i.p.v. stil verkeerd bij de gebruiker te landen.
 *
 * Bewust géén 502-mapping hier: deze functie reproduceert exact de status die de
 * facade vroeger zelf gooide. De per-consumer transportpolitiek (lees-pad → [mapUpstreamFout]
 * dat 5xx naar 502 maakt; aanmeld-pad → status-behoudend) blijft daar belegd, zodat
 * het externe gedrag ongewijzigd is.
 */
/**
 * Of een cache-fout een upstream-storing is (de cache zelf hapert) dan wel een client-/
 * contract-aanwijzing die status-behoudend hoort te propageren. Exhaustief náást [naApiFout],
 * waarmee de cache→transport-kennis op één plek belegd blijft; een nieuw [SessiecacheException]-
 * geval breekt ook hier de build. Het schrijfpad gebruikt dit om te beslissen of het na een
 * geslaagde magazijn-write de cache compenseert (invalidate + 502) of de status doorlaat —
 * zonder daarvoor een wegwerp-[WebApplicationException] te bouwen of statuscode-ranges te
 * reverse-engineeren.
 */
internal fun SessiecacheException.isStoring(): Boolean = when (this) {
    is SessiecacheException.OphalenMislukt,
    is SessiecacheException.Onbereikbaar,
    is SessiecacheException.Onleesbaar,
    -> true

    is SessiecacheException.NogNietGevuld,
    is SessiecacheException.OphalenBezig,
    is SessiecacheException.OngeldigeInvoer,
    is SessiecacheException.GeenActieveSessie,
    -> false
}

internal fun SessiecacheException.naApiFout(): WebApplicationException = when (this) {
    is SessiecacheException.NogNietGevuld -> WebApplicationException(message, this, Response.Status.CONFLICT)
    is SessiecacheException.OphalenBezig -> WebApplicationException(message, this, Response.Status.CONFLICT)
    is SessiecacheException.OphalenMislukt -> WebApplicationException(message, this, Response.Status.INTERNAL_SERVER_ERROR)
    is SessiecacheException.Onbereikbaar -> WebApplicationException(message, this, Response.Status.SERVICE_UNAVAILABLE)
    is SessiecacheException.Onleesbaar -> WebApplicationException(message, this, Response.Status.INTERNAL_SERVER_ERROR)
    is SessiecacheException.OngeldigeInvoer -> WebApplicationException(message, this, Response.Status.BAD_REQUEST)
    is SessiecacheException.GeenActieveSessie -> WebApplicationException(message, this, Response.Status.NOT_FOUND)
}

/**
 * Lees-pad-grens voor cache-facade-calls: vertaalt een [SessiecacheException] eerst
 * exhaustief naar zijn status ([naApiFout]) en past daarna dezelfde upstream-politiek
 * toe als op het magazijn ([mapUpstreamFout]) — een 5xx wordt 502, een 4xx (409 cache-
 * nog-niet-gevuld) propageert ongewijzigd. Zo blijft "502 = upstream-fout" gelden voor
 * de in-process cache zoals voorheen.
 */
internal inline fun <T> leesUitCache(log: Logger, context: String, block: () -> T): T =
    mapUpstreamFout(log, context) {
        try {
            block()
        } catch (e: SessiecacheException) {
            throw e.naApiFout()
        }
    }
