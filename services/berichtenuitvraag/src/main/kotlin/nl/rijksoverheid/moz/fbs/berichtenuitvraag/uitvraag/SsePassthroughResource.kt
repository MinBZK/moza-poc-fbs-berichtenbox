package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.smallrye.mutiny.Multi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
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

        return streamingClient.ophalen(xOntvanger)
            .onFailure().invoke { e ->
                // Upstream-fout (WAE/ProcessingException) = echte sessiecache-faal → error.
                // De rest is óf een client-disconnect óf een pijplijn-bug (serialisatie, NPE);
                // die zijn in deze Vert.x/Mutiny-stack niet op type te scheiden, dus warn als
                // veilige ondergrens — niet debug, anders verdwijnt een echte bug stil.
                if (e is WebApplicationException || e is ProcessingException) {
                    log.errorf(e, "SSE-passthrough: upstream sessiecache-fout")
                } else {
                    log.warnf(e, "SSE-passthrough afgebroken (client-disconnect of onverwachte fout)")
                }
            }
            .onFailure().transform { e ->
                // Normaliseer een PRE-STREAM upstream-fout naar het contract (spec
                // belooft alleen 401/500-equivalenten + 502 voor upstream-storing):
                // dezelfde allowlist als [mapUpstreamFout] — transport/non-4xx → 502,
                // echte 4xx behoudt zijn status. Zonder deze transform lekt een rauwe
                // upstream-503 of cache-404 buiten-contract naar de client.
                //
                // Beperking: zodra de eerste SSE-bytes geflusht zijn ligt de HTTP-status
                // vast; een mid-stream-fout kan die niet meer wijzigen (de stream breekt
                // dan af en wordt door de invoke-tak hierboven gelogd). Deze transform
                // raakt daarom alleen de pre-first-emission-fout — exact het geval waar
                // de status nog onderhandelbaar is. Mutiny maakt de twee hier niet op
                // type onderscheidbaar, maar dat is acceptabel: na flush negeert de
                // container een nieuwe status, dus de transform is dan een no-op-her-wrap.
                if (e is WebApplicationException && !isUpstreamTransportFout(e)) e else upstreamBadGateway("SSE-passthrough upstream-fout")
            }
    }

    private fun registreerLdvSubject(xOntvanger: String) =
        registreerLdvSubject(logboekContext, xOntvanger)

    private companion object {
        private val log: Logger = Logger.getLogger(SsePassthroughResource::class.java)
    }
}
