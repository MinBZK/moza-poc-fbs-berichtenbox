package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Schrijft naar magazijn (bron van waarheid) en sessiecache (afgeleide cache).
 * Magazijn-faal → fout naar client, cache niet aangeraakt. Magazijn-OK +
 * cache-faal → best-effort cache-invalidate (vervangt 'stale' door 'leeg'),
 * daarna 502 zodat de client weet dat de operatie niet volledig consistent
 * doorgevoerd is. De magazijn-operaties zijn op het magazijn zélf idempotent;
 * specifiek na een geslaagde DELETE kan een herhaalde uitvraag-call echter 404
 * geven, omdat de cache-entry dán geïnvalideerd is en `resolveMagazijn` het
 * bron-`magazijnId` niet meer vindt. De PATCH-happy-path heeft dit niet: die
 * werkt de cache-entry bij (verwijdert hem niet), dus de lookup blijft slagen.
 *
 * "Cache-faal" = transport-storing (timeout, connect-fout, 5xx upstream). 4xx
 * van de cache duidt op een contract-bug en wordt onveranderd doorgegeven; de
 * cache-state hoeft dan niet ge-invalideerd te worden.
 *
 * Multi-magazijn routering: we doen vóór elke write eerst een sessiecache-
 * lookup om het bron-`magazijnId` te bepalen (de cache is bron-van-waarheid
 * voor herkomst — een client-gegeven id zou een vector zijn om te schrijven
 * in een magazijn dat dit bericht niet bezit). Cache-miss → 404 propageert
 * naar de client; die hoort het bericht eerst opnieuw op te halen (de
 * sliding TTL van 60s dekt typische interactieve flows ruimschoots). Een
 * transport-fout op die lookup (timeout, connect-fout, 5xx) → 502: er is dan
 * nog niets naar het magazijn geschreven, dus dezelfde 502-semantiek als bij
 * een cache-faal ná de write.
 */
@ApplicationScoped
class BerichtBeheerService(
    @RestClient private val sessiecache: SessiecacheClient,
    private val magazijnRouter: MagazijnRouter,
) {

    fun patch(xOntvanger: String, berichtId: UUID, patch: BerichtPatch): Bericht {
        val magazijn = resolveMagazijn(xOntvanger, berichtId)

        mapUpstreamFout(log, "magazijn-PATCH") {
            magazijn.patchBericht(xOntvanger, berichtId, UitvraagDtoMapper.toMagazijnPatch(patch))
        }

        try {
            return sessiecache.patchBericht(xOntvanger, berichtId, patch)
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
            compensatieInvalidate(xOntvanger, berichtId)
            throw badGateway()
        } catch (e: ProcessingException) {
            log.errorf(e, "cache-PATCH transport-fout na geslaagde magazijn-PATCH; invalidate volgt. berichtId=%s", berichtId)
            compensatieInvalidate(xOntvanger, berichtId)
            throw badGateway()
        }
    }

    fun verwijder(xOntvanger: String, berichtId: UUID) {
        val magazijn = resolveMagazijn(xOntvanger, berichtId)

        mapUpstreamFout(log, "magazijn-DELETE") {
            magazijn.verwijderBericht(xOntvanger, berichtId)
        }

        try {
            sessiecache.verwijderBericht(xOntvanger, berichtId)
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
            compensatieInvalidate(xOntvanger, berichtId)
            throw badGateway()
        } catch (e: ProcessingException) {
            log.errorf(e, "cache-DELETE transport-fout na geslaagde magazijn-DELETE; invalidate volgt. berichtId=%s", berichtId)
            compensatieInvalidate(xOntvanger, berichtId)
            throw badGateway()
        }
    }

    private fun resolveMagazijn(xOntvanger: String, berichtId: UUID): MagazijnClient {
        // Cache-lookup vóór de write bepaalt het bron-magazijn. Transport-fout/5xx
        // → 502 (er is nog niets geschreven); 4xx (incl. 404 cache-miss) propageert.
        val bericht = mapUpstreamFout(log, "cache-lookup vóór magazijn-write (berichtId=$berichtId)") {
            sessiecache.bericht(xOntvanger, berichtId)
        }

        return magazijnRouter.forMagazijn(vereisMagazijnId(bericht, berichtId, log))
    }

    private fun compensatieInvalidate(xOntvanger: String, berichtId: UUID) {
        try {
            sessiecache.verwijderBericht(xOntvanger, berichtId)
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
