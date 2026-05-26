package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
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
 * doorgevoerd is. Alle operaties zijn idempotent — client mag retryen.
 */
@ApplicationScoped
class BerichtBeheerService(
    @RestClient private val sessiecache: SessiecacheClient,
    @RestClient private val magazijn: MagazijnClient,
) {

    fun patch(xOntvanger: String, berichtId: UUID, patch: BerichtPatch): Bericht {
        magazijn.patchBericht(xOntvanger, berichtId, UitvraagDtoMapper.toMagazijnPatch(patch))

        return try {
            sessiecache.patchBericht(xOntvanger, berichtId, patch)
        } catch (e: Exception) {
            log.warnf(e, "cache-PATCH faalde na geslaagde magazijn-PATCH; invalidate volgt. berichtId=%s", berichtId)
            compensatieInvalidate(xOntvanger, berichtId)
            throw badGateway()
        }
    }

    fun verwijder(xOntvanger: String, berichtId: UUID) {
        magazijn.verwijderBericht(xOntvanger, berichtId)

        try {
            sessiecache.verwijderBericht(xOntvanger, berichtId)
        } catch (e: Exception) {
            log.warnf(e, "cache-DELETE faalde na geslaagde magazijn-DELETE; invalidate volgt. berichtId=%s", berichtId)
            compensatieInvalidate(xOntvanger, berichtId)
            throw badGateway()
        }
    }

    private fun compensatieInvalidate(xOntvanger: String, berichtId: UUID) {
        try {
            sessiecache.verwijderBericht(xOntvanger, berichtId)
        } catch (e: Exception) {
            log.warnf(e, "compensatie-invalidate faalde; cache mogelijk stale tot TTL. berichtId=%s", berichtId)
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
