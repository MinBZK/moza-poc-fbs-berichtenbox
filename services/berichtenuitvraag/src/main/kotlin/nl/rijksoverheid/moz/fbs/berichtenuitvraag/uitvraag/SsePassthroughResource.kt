package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.smallrye.mutiny.Multi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
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
 * hier gedefinieerd. Triggert in de sessiecache het ophalen uit alle aangesloten
 * magazijnen en pijpt elk SSE-event 1-op-1 door naar de client.
 *
 * `X-Ontvanger` wordt lokaal gevalideerd (zelfde pattern als de gegenereerde
 * UitvraagApi): het endpoint zet de waarde in `logboekContext.dataSubjectId`
 * (AVG art. 30) en vóór die write moet de input integriteit hebben — anders
 * raakt een willekeurige header-waarde de LDV-audittrail.
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
    fun ophalen(
        @HeaderParam("X-Ontvanger")
        @NotNull
        @Pattern(regexp = ONTVANGER_PATTERN)
        xOntvanger: String,
    ): Multi<String> {
        registreerLdvSubject(xOntvanger)

        return streamingClient.ophalen(xOntvanger).onFailure().invoke { e ->
            // Upstream-fouten (WAE/ProcessingException) zijn echte sessiecache-faal → error.
            // De rest kan een verwachte client-disconnect zijn óf een bug in de stream-
            // pijplijn (serialisatie, NPE, …); die twee zijn hier nog niet op type te
            // onderscheiden. We loggen daarom op warn i.p.v. debug, zodat een echte bug
            // niet stil verdwijnt. Het concrete client-abort-type is in deze Vert.x/
            // Mutiny-stack (nog) niet te onderscheiden van een pijplijn-bug, dus blijft
            // warn de veilige ondergrens.
            if (e is jakarta.ws.rs.WebApplicationException || e is jakarta.ws.rs.ProcessingException) {
                log.errorf(e, "SSE-passthrough: upstream sessiecache-fout")
            } else {
                log.warnf(e, "SSE-passthrough afgebroken (client-disconnect of onverwachte fout)")
            }
        }
    }

    private fun registreerLdvSubject(xOntvanger: String) =
        registreerLdvSubject(logboekContext, xOntvanger)

    private companion object {
        private val log: Logger = Logger.getLogger(SsePassthroughResource::class.java)
    }
}
