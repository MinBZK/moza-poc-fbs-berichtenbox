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
 * Of een cache-fout een upstream-storing is (de cache zelf hapert) dan wel een client-/
 * contract-aanwijzing die status-behoudend hoort te propageren. Dekt alle gevallen af, náást
 * [naApiFout], waarmee de cache→transport-kennis op één plek belegd blijft; een nieuw
 * [SessiecacheException]-geval breekt ook hier de build. Het schrijfpad gebruikt dit om te beslissen of het na een
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

/**
 * Enige plek waar de gesloten [SessiecacheException]-hiërarchie naar een HTTP-status
 * wordt vertaald. De `when` dekt alle gevallen zónder `else`: een nieuw foutscenario in de
 * cache-library breekt hier de build i.p.v. stil verkeerd bij de gebruiker te landen.
 *
 * Bewust géén 502-mapping hier: deze functie levert puur de status die per cache-foutgeval
 * hoort. De per-consumer transportpolitiek (lees-pad → [mapUpstreamFout] dat 5xx naar 502
 * maakt; aanmeld-pad → status-behoudend) blijft daar belegd.
 */
internal fun SessiecacheException.naApiFout(): WebApplicationException = when (this) {
    is SessiecacheException.NogNietGevuld -> WebApplicationException(message, this, Response.Status.CONFLICT)
    is SessiecacheException.OphalenBezig -> WebApplicationException(message, this, Response.Status.CONFLICT)
    // Een mislukte ophaalronde is geen defect maar een toestand: het ophalen strandde (bv. de
    // voorkeurenbron gaf een serverfout) en opnieuw ophalen is de weg vooruit. 503 zegt dat,
    // en het is dezelfde status die `_ophalen` in precies deze situatie al geeft; 500 zou de
    // client naar een bug bij ons wijzen.
    is SessiecacheException.OphalenMislukt -> tijdelijkNietBeschikbaar(message, this)
    is SessiecacheException.Onbereikbaar -> tijdelijkNietBeschikbaar(message, this)
    is SessiecacheException.Onleesbaar -> WebApplicationException(message, this, Response.Status.INTERNAL_SERVER_ERROR)
    is SessiecacheException.OngeldigeInvoer -> WebApplicationException(message, this, Response.Status.BAD_REQUEST)
    is SessiecacheException.GeenActieveSessie -> WebApplicationException(message, this, Response.Status.NOT_FOUND)
}

/**
 * Een 503 waar opnieuw proberen zin heeft, met dezelfde `Retry-After` als de profiel-mapper op
 * zijn retry-bare 503 zet. Zonder die header staat de aanwijzing alleen in proza en moet een
 * client zelf een interval verzinnen.
 */
private fun tijdelijkNietBeschikbaar(message: String?, oorzaak: SessiecacheException) =
    WebApplicationException(
        message,
        oorzaak,
        Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Retry-After", RETRY_AFTER_SECONDEN).build(),
    )

/** Gelijk aan wat de profiel-mapper hanteert; één waarde zodat clients niet per endpoint hoeven te leren wachten. */
private const val RETRY_AFTER_SECONDEN = "30"

/**
 * Lees-pad-grens voor cache-facade-calls. De cache classificeert zijn eigen uitkomst al
 * precies ([SessiecacheException]); die status gaat daarom rechtstreeks naar de client via
 * [naApiFout] en wordt níét nog eens door de upstream-politiek gehaald. Dat laatste maakte
 * er eerder een 502 van, waarmee "de vorige ophaalronde is mislukt, haal opnieuw op" voor de
 * client niet meer te onderscheiden was van een infrastructuurstoring — precies het signaal
 * dat hij nodig heeft om te weten wat hem te doen staat.
 *
 * [mapUpstreamFout] blijft eromheen staan voor fouten die niet uit de cache-classificatie
 * komen: een transport-fout of een onverwachte upstream-status verdient nog steeds een 502.
 *
 * De lees-facademethoden (`lijst`/`zoek`/`bericht`) produceren alleen storing-fouten en de
 * 409-gating ([SessiecacheException.NogNietGevuld]/[SessiecacheException.OphalenBezig]); de
 * 400/404-gevallen ([SessiecacheException.OngeldigeInvoer]/[SessiecacheException.GeenActieveSessie])
 * ontstaan uitsluitend op de schrijfpaden en bereiken deze grens dus niet.
 */
internal inline fun <T> leesUitCache(log: Logger, context: String, block: () -> T): T =
    try {
        mapUpstreamFout(log, context, block)
    } catch (e: SessiecacheException) {
        val fout = e.naApiFout()

        // De 409-gating is het normale verloop — een client die leest vóór of tijdens het
        // ophalen krijgt hem standaard. Die op waarschuwingsniveau loggen zou het signaal
        // verwateren dat voor de echte storingen bedoeld is.
        if (e.isStoring()) {
            log.warnf("%s: cache-uitkomst %s → status %d", context, e.javaClass.simpleName, fout.response.status)
        } else {
            log.debugf("%s: cache-uitkomst %s → status %d", context, e.javaClass.simpleName, fout.response.status)
        }

        throw fout
    }
