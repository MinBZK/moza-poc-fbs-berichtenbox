package nl.rijksoverheid.moz.berichtensessiecache.berichten

import io.opentelemetry.api.trace.StatusCode
import io.smallrye.common.annotation.Blocking
import io.smallrye.mutiny.Multi
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
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
        @HeaderParam("X-Ontvanger") ontvanger: String?,
    ): Multi<MagazijnEvent> {
        if (ontvanger.isNullOrBlank()) {
            throw WebApplicationException("Header 'X-Ontvanger' is verplicht.", Response.Status.BAD_REQUEST)
        }

        logboekContext.dataSubjectId = ontvanger
        logboekContext.dataSubjectType = "ontvanger"

        val aggregation = service.ophalenBerichten(ontvanger)

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
                    logboekContext.status = StatusCode.ERROR
                    if (!emitter.isCancelled) emitter.fail(error)
                },
                { if (!emitter.isCancelled) emitter.complete() },
            )
        }
    }
}
