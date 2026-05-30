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
 * een herhaalde uitvraag-call kan echter 404 geven, omdat de bron-`magazijnId`
 * na de eerste write uit de sessiecache verdwijnt en `resolveMagazijn` dan geen
 * route meer vindt (zie de spec-correctie in commit b34f9fe).
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
            if (!isUpstreamTransportFout(e)) {
                // 4xx = contract-bug, geen transport-storing: niet compenseren (zie
                // herverpakCache4xx), wél loggen. Status propageert; body niet (facade
                // lekt geen cache-internals/PII). Cache kan tot de TTL stale blijven.
                log.warnf(e, "cache-PATCH 4xx ná geslaagde magazijn-PATCH; magazijn↔cache mogelijk stale tot TTL. berichtId=%s", berichtId)

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
            if (!isUpstreamTransportFout(e)) {
                // 4xx = contract-bug, geen transport-storing: niet compenseren (zie
                // herverpakCache4xx), wél loggen. Status propageert; body niet (facade
                // lekt geen cache-internals/PII). Cache kan tot de TTL stale blijven.
                log.warnf(e, "cache-DELETE 4xx ná geslaagde magazijn-DELETE; magazijn↔cache mogelijk stale tot TTL. berichtId=%s", berichtId)

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

        return magazijnRouter.forMagazijn(bericht.magazijnId)
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
    private fun logCompensatieFout(e: Exception, berichtId: UUID) =
        log.errorf(e, "compensatie-invalidate faalde ná cache-write-faal; cache stale tot TTL. berichtId=%s", berichtId)

    // Herverpak een cache-4xx tot een status- en type-behoudende exception zonder de
    // upstream-response-body, zodat de client de juiste semantiek (404/403/…) krijgt
    // maar sessiecache-internals niet via de facade lekken.
    private fun herverpakCache4xx(e: WebApplicationException): WebApplicationException {
        val status = e.response?.status ?: Response.Status.BAD_GATEWAY.statusCode

        return when (status) {
            404 -> NotFoundException("cache-operatie geweigerd (404)")
            403 -> ForbiddenException("cache-operatie geweigerd (403)")
            else -> WebApplicationException("cache-operatie geweigerd ($status)", status)
        }
    }

    // Jakarta REST 3.1 levert geen BadGatewayException; WebApplicationException
    // met expliciete 502-status geeft dezelfde semantiek voor downstream client.
    private fun badGateway(): WebApplicationException =
        WebApplicationException("cache-update faalde; magazijn bijgewerkt", Response.Status.BAD_GATEWAY)

    private companion object {
        private val log: Logger = Logger.getLogger(BerichtBeheerService::class.java)
    }
}
