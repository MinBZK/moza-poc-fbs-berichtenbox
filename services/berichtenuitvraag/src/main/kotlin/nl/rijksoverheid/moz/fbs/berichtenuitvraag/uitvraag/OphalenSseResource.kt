package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.opentelemetry.api.trace.StatusCode
import io.smallrye.common.annotation.Blocking
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.subscription.MultiEmitter
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
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.EventType
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MagazijnEvent
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.RestStreamElementType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSE-endpoint voor `GET /berichten/_ophalen`. De jaxrs-spec generator
 * ondersteunt geen `Multi<>` return-types; daarom is dit endpoint expliciet
 * hier gedefinieerd. Start het ophalen uit alle voor de ontvanger relevante
 * magazijnen via de in-process [Sessiecache]-facade en streamt elk
 * voortgangs-event als SSE naar de client.
 *
 * `X-Ontvanger` wordt lokaal gevalideerd (zelfde pattern als de gegenereerde
 * UitvraagApi): het endpoint zet de waarde in `logboekContext.dataSubjectId`
 * (AVG art. 30) en vóór die write moet de input integriteit hebben — anders
 * raakt een willekeurige header-waarde de LDV-audittrail.
 */
@Path(ApiInfo.BASE_PATH + "/berichten/_ophalen")
@ApplicationScoped
class OphalenSseResource(
    private val sessiecache: Sessiecache,
    private val logboekContext: LogboekContext,
) {
    private val log = Logger.getLogger(OphalenSseResource::class.java)

    @GET
    @Blocking
    @Logboek(name = "uitvraag-ophalen-sse", processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun ophalen(
        @HeaderParam("X-Ontvanger")
        @NotNull
        @Pattern(regexp = ONTVANGER_PATTERN)
        xOntvanger: String,
    ): Multi<MagazijnEvent> {
        registreerLdvSubject(logboekContext, xOntvanger)

        val ontvangerId = Identificatienummer.fromHeader(xOntvanger)
        val aggregation = sessiecache.ophalen(ontvangerId)

        // SSE-stream is een observer: stuurt events door zolang de client verbonden is.
        // Client disconnect stopt alleen de emitter, de aggregatie loopt onafhankelijk door.
        return Multi.createFrom().emitter { emitter ->
            koppelAggregatieAanEmitter(aggregation, emitter, ontvangerId)
        }
    }

    private fun koppelAggregatieAanEmitter(
        aggregation: Multi<MagazijnEvent>,
        emitter: MultiEmitter<in MagazijnEvent>,
        ontvangerId: Identificatienummer,
    ) {
        // Markeert of de stream normaal eindigde (completion of fout). Blijft false bij
        // client-disconnect vóór het finale event, zodat onTermination dat kan loggen.
        val streamAfgerond = AtomicBoolean(false)

        aggregation.subscribe().with(
            { event ->
                registreerLogboekStatus(event)
                if (!emitter.isCancelled) emitter.emit(event)
            },
            { error ->
                streamAfgerond.set(true)
                logStreamFout(error, ontvangerId)
                if (!emitter.isCancelled) emitter.fail(error)
            },
            {
                streamAfgerond.set(true)
                if (!emitter.isCancelled) emitter.complete()
            },
        )

        emitter.onTermination {
            // De aggregatie (magazijn-calls + cache-writes) loopt bewust door na een
            // client-disconnect zodat de cache alsnog gevuld raakt; we cancelen de
            // subscription dus NIET. Wel loggen we een vroegtijdige terminatie (client
            // weg vóór het finale event) zodat afgebroken ophaalsessies zichtbaar zijn
            // in observability i.p.v. stil te verdwijnen. ontvanger.type, geen waarde (PII).
            if (!streamAfgerond.get()) {
                log.infof(
                    "SSE-client verbroken vóór completion (ontvanger.type=%s); aggregatie loopt door om cache te vullen",
                    ontvangerId.type,
                )
            }
        }
    }

    /** Zet logboek-status op basis van het finale event; tussentijdse events wijzigen niets. */
    private fun registreerLogboekStatus(event: MagazijnEvent) {
        when (event.event) {
            EventType.OPHALEN_GEREED ->
                logboekContext.status = if (event.mislukt == 0) StatusCode.OK else StatusCode.ERROR
            EventType.OPHALEN_FOUT ->
                logboekContext.status = StatusCode.ERROR
            else -> { /* geen actie voor tussentijdse events */ }
        }
    }

    /**
     * Async-aggregatie-fouten ná SSE-stream-open hebben geen JAX-RS-mapper-
     * pad meer (response-headers zijn al verzonden); zonder log hier verdwijnt
     * de fout uit server-side observability. ontvanger.type i.p.v. waarde
     * (BSN/RSIN PII; type-context volstaat voor incident-triage). Géén
     * stacktrace meelgeven: cause-chain van lagere clients kan upstream-URL
     * met BSN bevatten (precedent: ProfielServiceFoutExceptionMapper).
     */
    private fun logStreamFout(error: Throwable, ontvangerId: Identificatienummer) {
        logboekContext.status = StatusCode.ERROR
        log.errorf(
            "SSE-stream gefaald na open (ontvanger.type=%s, cause=%s, msg-class=%s)",
            ontvangerId.type,
            error.cause?.javaClass?.simpleName ?: "geen",
            error.javaClass.simpleName,
        )
    }
}
