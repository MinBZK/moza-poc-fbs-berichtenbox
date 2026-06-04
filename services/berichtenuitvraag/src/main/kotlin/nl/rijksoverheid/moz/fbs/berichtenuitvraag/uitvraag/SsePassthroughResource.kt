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
                // WAE/ProcessingException = echte sessiecache-faal → error. De rest is
                // client-disconnect óf pijplijn-bug (serialisatie/NPE), in deze Vert.x/Mutiny-
                // stack niet op type te scheiden → warn als ondergrens (niet debug, anders
                // verdwijnt een echte bug stil). Type expliciet loggen zodat een dashboard de
                // benigne disconnects kan wegfilteren en een echte bug grepbaar blijft.
                if (e is WebApplicationException || e is ProcessingException) {
                    log.errorf(e, "SSE-passthrough: upstream sessiecache-fout")
                } else {
                    log.warnf(e, "SSE-passthrough afgebroken (client-disconnect of onverwachte fout); type=%s", e::class.java.name)
                }
            }
            .onFailure().transform { e ->
                // Zet de upstream-fout om naar een getypeerde terminal-fout (allowlist:
                // echte 4xx behoudt status, transport/non-4xx → 502). Zie [ssePreStreamFout].
                //
                // Belangrijk: RESTEasy commit de 200-SSE-headers al bij subscriptie, dus
                // deze status bereikt de client niet meer — de stream termineert onder de
                // reeds verzonden 200 en de client ziet de storing via het uitblijven van
                // het OPHALEN_GEREED-event (en de per-magazijn FOUT-events). De transform
                // dient hier dus voor nette, getypeerde Mutiny-afhandeling + logging, niet
                // om de wire-status te zetten. De `_ophalen`-spec belooft daarom géén 502.
                ssePreStreamFout(e)
            }
    }

    private fun registreerLdvSubject(xOntvanger: String) =
        registreerLdvSubject(logboekContext, xOntvanger)

    private companion object {
        private val log: Logger = Logger.getLogger(SsePassthroughResource::class.java)
    }
}
