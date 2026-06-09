package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Schrijft naar magazijn (bron van waarheid) en sessiecache (afgeleide cache).
 * Magazijn-faal → fout naar client, cache niet aangeraakt. Magazijn-OK + cache-storing
 * → best-effort invalidate ('stale' wordt 'leeg'), dan 502 om de inconsistentie te
 * signaleren. "Cache-storing" = [SessiecacheException.isStoring] (Redis-storing, schrijf-
 * contentie, corruptie, mislukte ophaling); een niet-storing cache-fout duidt op een
 * contract-bug en gaat status-behoudend door zonder invalidatie.
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
    private val sessiecache: Sessiecache,
    private val magazijnRouter: MagazijnRouter,
) {

    fun patch(ontvanger: String, berichtId: UUID, magazijnId: String, patch: BerichtPatch): Bericht {
        // Lege patch afwijzen vóór de magazijn-write (spec: minProperties 1; Bean
        // Validation dwingt dat niet af op het gegenereerde model). Zonder deze check
        // zou een no-op het magazijn raken en pas in de cache-laag op 400 stranden —
        // ná de write, met een onnodige desync-alert tot gevolg.
        if (patch.status == null && patch.map == null) {
            throw WebApplicationException(
                "Minimaal één van 'status' of 'map' is vereist (geen geldige waarde meegegeven).",
                Response.Status.BAD_REQUEST,
            )
        }

        val magazijn = magazijnRouter.forMagazijn(magazijnId)

        mapUpstreamFout(log, "magazijn-PATCH") {
            magazijn.patchBericht(ontvanger, berichtId, UitvraagDtoMapper.toMagazijnPatch(patch))
        }

        val ontvangerId = Identificatienummer.fromHeader(ontvanger)

        val bijgewerkt = try {
            sessiecache.werkBerichtBij(ontvangerId, berichtId, UitvraagDtoMapper.toLeesstatus(patch.status), patch.map)
        } catch (e: SessiecacheException) {
            if (!e.isStoring()) {
                // Een niet-storing fout ná een geslaagde magazijn-write is een contract-bug,
                // geen transport-storing: de client-request passeerde immers al de magazijn-
                // validatie, dus hoort de cache hier geen client-fout te geven. Niet compenseren,
                // maar wél met hetzelfde alert-anker als de dubbele-faal-tak loggen: magazijn↔cache
                // desyncen zonder self-heal tot de TTL — dezelfde operationele impact als een
                // storing-desync, dus géén stille warnf maar een alertbare errorf. Status propageert.
                log.errorf(e, "%s cache-PATCH niet-storing ná geslaagde magazijn-PATCH; magazijn↔cache stale tot TTL. berichtId=%s", ALERT_CACHE_DESYNC, berichtId)

                throw e.naApiFout()
            }

            log.errorf(e, "cache-PATCH storing na geslaagde magazijn-PATCH; invalidate volgt. berichtId=%s", berichtId)
            invalideerCacheNaMagazijnWrite(ontvangerId, berichtId)
            throw badGateway(e)
        }

        if (bijgewerkt == null) {
            // Bericht bestaat in het magazijn (de write slaagde) maar niet (meer) in de
            // cache — verlopen TTL of eerdere invalidate. Zelfde alert-anker: de magazijn-
            // mutatie is doorgevoerd terwijl de client een 404 ziet; zichtbaar maken i.p.v.
            // stil als gewone not-found wegschrijven.
            log.errorf("%s cache-PATCH miste het bericht ná geslaagde magazijn-PATCH; magazijn↔cache stale tot TTL. berichtId=%s", ALERT_CACHE_DESYNC, berichtId)

            throw NotFoundException("Bericht niet gevonden in cache")
        }

        return UitvraagDtoMapper.toApiBericht(bijgewerkt)
    }

    fun verwijder(ontvanger: String, berichtId: UUID, magazijnId: String) {
        val magazijn = magazijnRouter.forMagazijn(magazijnId)

        mapUpstreamFout(log, "magazijn-DELETE") {
            magazijn.verwijderBericht(ontvanger, berichtId)
        }

        val ontvangerId = Identificatienummer.fromHeader(ontvanger)

        try {
            // Idempotente invalidate: "niet in cache" is geen fout, dus geen 404-pad hier.
            sessiecache.verwijder(ontvangerId, berichtId)
        } catch (e: SessiecacheException) {
            if (!e.isStoring()) {
                log.errorf(e, "%s cache-DELETE niet-storing ná geslaagde magazijn-DELETE; magazijn↔cache stale tot TTL. berichtId=%s", ALERT_CACHE_DESYNC, berichtId)

                throw e.naApiFout()
            }

            log.errorf(e, "cache-DELETE storing na geslaagde magazijn-DELETE; invalidate volgt. berichtId=%s", berichtId)
            invalideerCacheNaMagazijnWrite(ontvangerId, berichtId)
            throw badGateway(e)
        }
    }

    private fun invalideerCacheNaMagazijnWrite(ontvanger: Identificatienummer, berichtId: UUID) {
        try {
            sessiecache.verwijder(ontvanger, berichtId)
        } catch (e: SessiecacheException) {
            // Dubbele cache-faal: de write naar de cache faalde én de compenserende
            // invalidate faalde. De cache blijft stale tot de TTL zonder self-heal —
            // errorf (niet warnf) zodat een aanhoudende cache-outage alertbaar is i.p.v.
            // te verdrinken in warnings. Vooraan een stabiele marker zodat de Loki-alert-
            // rule daarop ankert i.p.v. op de (vertaalbare) proza. Pak bij de volgende
            // project-brede metric-toevoeging een echte counter op.
            log.errorf(e, "%s compensatie-invalidate faalde ná cache-write-faal; cache stale tot TTL. berichtId=%s", ALERT_CACHE_DESYNC, berichtId)
        }
    }

    // 502: magazijn-write slaagde, maar de cache-update faalde (zie [upstreamBadGateway]).
    // De cache-fout gaat als cause mee zodat de oorzaak in exception-keten-logging behouden blijft.
    private fun badGateway(cause: Throwable): WebApplicationException =
        upstreamBadGateway("cache-update faalde; magazijn bijgewerkt", cause)

    private companion object {
        private val log: Logger = Logger.getLogger(BerichtBeheerService::class.java)

        // Stabiel alert-anker (los van vertaalbare proza) voor de Loki-rule die op
        // blijvende magazijn↔cache-desync zonder self-heal moet alarmeren. Wijzig de
        // waarde niet zonder de bijbehorende alert-rule mee te verhuizen.
        private const val ALERT_CACHE_DESYNC = "FBS_ALERT[cache_desync_no_selfheal]"
    }
}
