package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Schrijft naar magazijn (bron van waarheid) en sessiecache (afgeleide cache).
 * Magazijn-faal → fout naar client, cache niet aangeraakt. Magazijn-OK + cache-faal
 * → best-effort invalidate ('stale' wordt 'leeg'), dan 502 om de inconsistentie te
 * signaleren. "Cache-faal" = transport-storing (timeout, connect-fout, 5xx); een 4xx
 * duidt op een contract-bug en gaat onveranderd door zonder invalidatie.
 *
 * De client geeft de `magazijnId` mee (uit de GET-response, `Bericht.magazijnId` of
 * `BerichtSamenvatting.magazijnId`). Daarmee hoeven we het bron-magazijn niet meer
 * uit de cache te halen vóór de write, en is de write-flow onafhankelijk van de
 * cache-state — een verlopen cache leidt niet langer tot een 404 op PATCH/DELETE.
 * Een onbekende of foute `magazijnId` wordt door het magazijn afgehandeld (404/403);
 * een `magazijnId` die niet in de config staat geeft 502 via [MagazijnRouter].
 */
@ApplicationScoped
class BerichtBeheerService(
    @RestClient private val sessiecache: SessiecacheClient,
    private val magazijnRouter: MagazijnRouter,
) {

    fun patch(ontvanger: String, berichtId: UUID, magazijnId: String, patch: BerichtPatch): Bericht {
        val magazijn = magazijnRouter.forMagazijn(magazijnId)

        mapUpstreamFout(log, "magazijn-PATCH") {
            magazijn.patchBericht(ontvanger, berichtId, UitvraagDtoMapper.toMagazijnPatch(patch))
        }

        try {
            return sessiecache.patchBericht(ontvanger, berichtId, patch)
        } catch (e: WebApplicationException) {
            if (!isUpstreamStoring(e)) {
                // 4xx ná een geslaagde magazijn-write is een contract-bug, geen transport-
                // storing: de client-request passeerde immers al de magazijn-validatie, dus
                // hoort de cache hier geen 4xx te geven. Niet compenseren (zie herverpakCache4xx),
                // maar wél met hetzelfde alert-anker als de dubbele-faal-tak loggen: magazijn↔cache
                // desyncen zonder self-heal tot de TTL — dezelfde operationele impact als een 5xx-
                // desync, dus géén stille warnf maar een alertbare errorf. Status propageert; body
                // niet (facade lekt geen cache-internals/PII).
                log.errorf(e, "%s cache-PATCH 4xx ná geslaagde magazijn-PATCH; magazijn↔cache stale tot TTL. berichtId=%s", ALERT_CACHE_DESYNC, berichtId)

                throw herverpakCache4xx(e)
            }

            log.errorf(e, "cache-PATCH 5xx na geslaagde magazijn-PATCH; invalidate volgt. berichtId=%s", berichtId)
            invalideerCacheNaMagazijnWrite(ontvanger, berichtId)
            throw badGateway()
        } catch (e: ProcessingException) {
            log.errorf(e, "cache-PATCH transport-fout na geslaagde magazijn-PATCH; invalidate volgt. berichtId=%s", berichtId)
            invalideerCacheNaMagazijnWrite(ontvanger, berichtId)
            throw badGateway()
        }
    }

    fun verwijder(ontvanger: String, berichtId: UUID, magazijnId: String) {
        val magazijn = magazijnRouter.forMagazijn(magazijnId)

        mapUpstreamFout(log, "magazijn-DELETE") {
            magazijn.verwijderBericht(ontvanger, berichtId)
        }

        try {
            sessiecache.verwijderBericht(ontvanger, berichtId)
        } catch (e: WebApplicationException) {
            if (!isUpstreamStoring(e)) {
                // 4xx ná een geslaagde magazijn-write is een contract-bug, geen transport-
                // storing: de client-request passeerde immers al de magazijn-validatie, dus
                // hoort de cache hier geen 4xx te geven. Niet compenseren (zie herverpakCache4xx),
                // maar wél met hetzelfde alert-anker als de dubbele-faal-tak loggen: magazijn↔cache
                // desyncen zonder self-heal tot de TTL — dezelfde operationele impact als een 5xx-
                // desync, dus géén stille warnf maar een alertbare errorf. Status propageert; body
                // niet (facade lekt geen cache-internals/PII).
                log.errorf(e, "%s cache-DELETE 4xx ná geslaagde magazijn-DELETE; magazijn↔cache stale tot TTL. berichtId=%s", ALERT_CACHE_DESYNC, berichtId)

                throw herverpakCache4xx(e)
            }

            log.errorf(e, "cache-DELETE 5xx na geslaagde magazijn-DELETE; invalidate volgt. berichtId=%s", berichtId)
            invalideerCacheNaMagazijnWrite(ontvanger, berichtId)
            throw badGateway()
        } catch (e: ProcessingException) {
            log.errorf(e, "cache-DELETE transport-fout na geslaagde magazijn-DELETE; invalidate volgt. berichtId=%s", berichtId)
            invalideerCacheNaMagazijnWrite(ontvanger, berichtId)
            throw badGateway()
        }
    }

    private fun invalideerCacheNaMagazijnWrite(ontvanger: String, berichtId: UUID) {
        try {
            sessiecache.verwijderBericht(ontvanger, berichtId)
        } catch (e: WebApplicationException) {
            logCompensatieFout(e, berichtId)
        } catch (e: ProcessingException) {
            logCompensatieFout(e, berichtId)
        }
    }

    // Dubbele cache-faal: de write naar de cache faalde én de compenserende invalidate
    // faalde. De cache blijft stale tot de TTL zonder self-heal — errorf (niet warnf)
    // zodat een aanhoudende cache-outage alertbaar is i.p.v. te verdrinken in warnings.
    // Vooraan een stabiele marker zodat de Loki-alert-rule daarop ankert i.p.v. op de
    // (vertaalbare) proza: een rephrase van de zin mag de alert niet stil slopen. Pak
    // bij de volgende project-brede metric-toevoeging (review M17) een echte counter op.
    private fun logCompensatieFout(e: Exception, berichtId: UUID) =
        log.errorf(e, "%s compensatie-invalidate faalde ná cache-write-faal; cache stale tot TTL. berichtId=%s", ALERT_CACHE_DESYNC, berichtId)

    // Herverpak een cache-4xx tot een status- en type-behoudende exception zonder de
    // upstream-response-body, zodat de client de juiste semantiek (404/403/…) krijgt
    // maar sessiecache-internals niet via de facade lekken.
    private fun herverpakCache4xx(e: WebApplicationException): WebApplicationException {
        // Invariant: alleen aangeroepen in de !isUpstreamStoring-tak, en isUpstreamStoring
        // geeft true (→ andere tak) zodra response == null. Een null hier is dus geen
        // verwachte 4xx maar een geschonden interne aanname → luidruchtig falen (500 via
        // UncaughtExceptionMapper) i.p.v. een 4xx-bedoelde fout stil als 502 herverpakken.
        val status = e.response?.status
            ?: error("herverpakCache4xx zonder response; isUpstreamStoring had dit als 502 moeten afvangen")

        return when (status) {
            404 -> NotFoundException("cache-operatie geweigerd (404)")
            403 -> ForbiddenException("cache-operatie geweigerd (403)")
            else -> WebApplicationException("cache-operatie geweigerd ($status)", status)
        }
    }

    // 502: magazijn-write slaagde, maar de cache-update faalde (zie [upstreamBadGateway]).
    private fun badGateway(): WebApplicationException =
        upstreamBadGateway("cache-update faalde; magazijn bijgewerkt")

    private companion object {
        private val log: Logger = Logger.getLogger(BerichtBeheerService::class.java)

        // Stabiel alert-anker (los van vertaalbare proza) voor de Loki-rule die op
        // blijvende magazijn↔cache-desync zonder self-heal moet alarmeren. Wijzig de
        // waarde niet zonder de bijbehorende alert-rule mee te verhuizen.
        private const val ALERT_CACHE_DESYNC = "FBS_ALERT[cache_desync_no_selfheal]"
    }
}
