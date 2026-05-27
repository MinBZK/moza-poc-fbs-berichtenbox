package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.opentelemetry.api.trace.StatusCode
import io.smallrye.common.annotation.Blocking
import io.smallrye.mutiny.Multi
import jakarta.inject.Inject
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.RestStreamElementType

/**
 * Aparte JAX-RS resource voor het SSE ophaal-endpoint.
 *
 * Dit endpoint is bewust gescheiden van [BerichtensessiecacheResource] omdat de
 * jaxrs-spec OpenAPI generator geen `Multi<T>` (SSE) return-types kan genereren.
 * Het pad `/berichten/_ophalen` overlapt niet met de gegenereerde interface-paden.
 */
@Path("/api/v1/berichten/_ophalen")
class BerichtenOphalenResource(
    private val service: BerichtensessiecacheService,
) {
    private val log = Logger.getLogger(BerichtenOphalenResource::class.java)

    @Inject
    lateinit var logboekContext: LogboekContext

    @GET
    @Blocking
    @Logboek(
        name = "ophalen-berichten-uit-magazijnen",
        processingActivityId = "https://register.example.com/verwerkingen/berichten-ophalen-aggregatie",
    )
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun ophalenBerichten(
        // Hand-rolled SSE-endpoint krijgt geen Bean-Validation via de gegenereerde JAX-RS
        // interface; @Pattern + @Size hier spiegelen de OntvangerHeader-parameter uit de
        // OpenAPI-spec zodat input-validatie aan de rand consistent is met de gegenereerde
        // endpoints. Voorkomt dat attacker-controlled prefix doorlekt naar Problem.detail
        // via DomainValidationException.
        @HeaderParam("X-Ontvanger")
        @Pattern(regexp = "^(BSN:[0-9]{9}|RSIN:[0-9]{9}|KVK:[0-9]{8}|OIN:[0-9]{20})$")
        @Size(min = 12, max = 24)
        ontvanger: String?,
    ): Multi<MagazijnEvent> {
        if (ontvanger.isNullOrBlank()) {
            throw WebApplicationException("Header 'X-Ontvanger' is verplicht.", Response.Status.BAD_REQUEST)
        }

        val ontvangerId = Identificatienummer.fromHeader(ontvanger)

        logboekContext.dataSubjectId = ontvangerId.waarde
        logboekContext.dataSubjectType = "ontvanger"

        val aggregation = service.ophalenBerichten(ontvangerId)

        // SSE-stream is een observer: stuurt events door zolang de client verbonden is.
        // Client disconnect stopt alleen de emitter, de aggregatie loopt onafhankelijk door.
        return Multi.createFrom().emitter { emitter ->
            aggregation.subscribe().with(
                { event ->
                    // Zet logboek status op basis van het finale event
                    when (event.event) {
                        EventType.OPHALEN_GEREED ->
                            logboekContext.status = if (event.mislukt == 0) StatusCode.OK else StatusCode.ERROR
                        EventType.OPHALEN_FOUT ->
                            logboekContext.status = StatusCode.ERROR
                        else -> { /* geen actie voor tussentijdse events */ }
                    }
                    if (!emitter.isCancelled) emitter.emit(event)
                },
                { error ->
                    // Async-aggregatie-fouten ná SSE-stream-open hebben geen JAX-RS-mapper-
                    // pad meer (response-headers zijn al verzonden); zonder log hier verdwijnt
                    // de fout uit server-side observability. ontvanger.type i.p.v. waarde
                    // (BSN/RSIN PII; type-context volstaat voor incident-triage). Géén
                    // stacktrace meelgeven: cause-chain van lagere clients kan upstream-URL
                    // met BSN bevatten (precedent: ProfielServiceFoutExceptionMapper).
                    logboekContext.status = StatusCode.ERROR
                    log.errorf(
                        "SSE-stream gefaald na open (ontvanger.type=%s, cause=%s, msg-class=%s)",
                        ontvangerId.type,
                        error.cause?.javaClass?.simpleName ?: "geen",
                        error.javaClass.simpleName,
                    )
                    if (!emitter.isCancelled) emitter.fail(error)
                },
                { if (!emitter.isCancelled) emitter.complete() },
            )
        }
    }
}
