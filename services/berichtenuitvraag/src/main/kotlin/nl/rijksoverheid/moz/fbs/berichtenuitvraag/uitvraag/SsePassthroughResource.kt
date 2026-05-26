package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.smallrye.mutiny.Multi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

/**
 * SSE-passthrough voor `GET /berichten/_ophalen`. De jaxrs-spec generator
 * ondersteunt geen `Multi<>` return-types; daarom is dit endpoint expliciet
 * hier gedefinieerd. Triggert in de sessiecache het ophalen van alle mappen
 * en pijpt elk event 1-op-1 door naar de client.
 */
@Path(ApiInfo.BASE_PATH + "/berichten/_ophalen")
@ApplicationScoped
class SsePassthroughResource(
    @RestClient private val streamingClient: SessiecacheSseClient,
    private val logboekContext: LogboekContext,
) {

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Logboek(name = "uitvraag-ophalen-sse", processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    fun ophalen(@HeaderParam("X-Ontvanger") xOntvanger: String): Multi<String> {
        registreerLdvSubject(xOntvanger)

        return streamingClient.ophalen(xOntvanger).onFailure().invoke { e ->
            log.warnf(e, "SSE-passthrough faalde tijdens streaming")
        }
    }

    private fun registreerLdvSubject(xOntvanger: String) {
        val delen = xOntvanger.split(':', limit = 2)

        if (delen.size == 2) {
            logboekContext.dataSubjectId = delen[1]
            logboekContext.dataSubjectType = delen[0]
        }
    }

    private companion object {
        private val log: Logger = Logger.getLogger(SsePassthroughResource::class.java)
    }
}
