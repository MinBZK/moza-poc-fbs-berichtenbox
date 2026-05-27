package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.opentelemetry.api.trace.StatusCode
import jakarta.ws.rs.Path
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.AggregationStatus as ApiAggregationStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.BerichtInput
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.BerichtLinks
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.BerichtResponse
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.BerichtStatus as ApiBerichtStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.BerichtStatusUpdate
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.BerichtensessiecacheResponse
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.Link
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.PaginationLinks
import org.jboss.logging.Logger
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

@Path("/api/v1/berichten")
class BerichtensessiecacheResource(
    private val berichtensessiecacheService: BerichtensessiecacheService,
    private val logboekContext: LogboekContext,
    @param:Context private val uriInfo: UriInfo,
) : nl.rijksoverheid.moz.fbs.berichtensessiecache.api.BerichtenApi {

    private val log = Logger.getLogger(BerichtensessiecacheResource::class.java)

    // De processingActivityId-URL's hieronder zijn hardcoded. Runtime-override via
    // `logboekContext.processingActivityId = ...` werkt niet: de LogboekInterceptor
    // schrijft de annotation-waarde naar de context ná `proceed()` (vlak vóór
    // addLogboekContextToSpan), dus elke method-body-override wordt overschreven.
    // Configureerbaarheid vereist een aanpassing in de logboekdataverwerking-wrapper.

    @Logboek(
        name = "ophalen-berichtensessiecache",
        processingActivityId = "https://register.example.com/verwerkingen/berichtensessiecache-ophalen",
    )
    override fun getBerichten(
        xOntvanger: String?,
        page: Int?,
        pageSize: Int?,
        afzender: String?,
    ): BerichtensessiecacheResponse {
        val (ontvangerId, aggregation) = requireGereedStatus(xOntvanger)

        logboekContext.dataSubjectId = ontvangerId.waarde
        logboekContext.dataSubjectType = "ontvanger"

        val p = page ?: 0
        val ps = (pageSize ?: 20).coerceAtMost(100)
        val result = awaitOrServiceUnavailable {
            berichtensessiecacheService.getBerichten(p, ps, ontvangerId, afzender)
        }
        logboekContext.status = StatusCode.OK
        return result.toResponse(aggregation, afzender)
    }

    @Logboek(
        name = "ophalen-bericht-bij-id",
        processingActivityId = "https://register.example.com/verwerkingen/bericht-ophalen",
    )
    override fun getBerichtById(berichtId: UUID, xOntvanger: String?): BerichtResponse {
        // Log berichtId als primair data subject (dit is de lookup-sleutel van dit endpoint)
        logboekContext.dataSubjectId = berichtId.toString()
        logboekContext.dataSubjectType = "berichtId"

        val (ontvangerId, _) = requireGereedStatus(xOntvanger)

        val bericht = awaitOrServiceUnavailable {
            berichtensessiecacheService.getBerichtById(berichtId, ontvangerId)
        } ?: throw WebApplicationException(
            "Bericht niet gevonden", Response.Status.NOT_FOUND,
        )

        logboekContext.status = StatusCode.OK
        return bericht.toResponse()
    }

    @Logboek(
        name = "zoeken-berichten",
        processingActivityId = "https://register.example.com/verwerkingen/berichten-zoeken",
    )
    override fun zoekBerichten(
        q: String,
        xOntvanger: String?,
        page: Int?,
        pageSize: Int?,
        afzender: String?,
    ): BerichtensessiecacheResponse {
        val (ontvangerId, aggregation) = requireGereedStatus(xOntvanger)

        logboekContext.dataSubjectId = ontvangerId.waarde
        logboekContext.dataSubjectType = "ontvanger"

        val p = page ?: 0
        val ps = (pageSize ?: 20).coerceAtMost(100)
        val result = awaitOrServiceUnavailable {
            berichtensessiecacheService.zoekBerichten(q, p, ps, ontvangerId, afzender)
        }
        logboekContext.status = StatusCode.OK
        return result.toResponse(aggregation, afzender)
    }

    @Logboek(
        name = "bijwerken-berichtstatus",
        processingActivityId = "https://register.example.com/verwerkingen/berichtstatus-bijwerken",
    )
    override fun updateBerichtStatus(
        berichtId: UUID,
        xOntvanger: String?,
        berichtStatusUpdate: BerichtStatusUpdate,
    ): BerichtResponse {
        logboekContext.dataSubjectId = berichtId.toString()
        logboekContext.dataSubjectType = "berichtId"

        val ontvangerId = requireOntvanger(xOntvanger)

        val bericht = awaitOrServiceUnavailable {
            berichtensessiecacheService.updateBerichtStatus(berichtId, ontvangerId, berichtStatusUpdate.status.toString())
        } ?: throw WebApplicationException(
            "Bericht niet gevonden", Response.Status.NOT_FOUND,
        )

        logboekContext.status = StatusCode.OK
        return bericht.toResponse()
    }

    @Logboek(
        name = "toevoegen-bericht",
        processingActivityId = "https://register.example.com/verwerkingen/bericht-toevoegen",
    )
    override fun addBericht(
        xOntvanger: String?,
        berichtInput: BerichtInput,
    ): BerichtResponse {
        val ontvangerId = requireOntvanger(xOntvanger)

        logboekContext.dataSubjectId = ontvangerId.waarde
        logboekContext.dataSubjectType = "ontvanger"

        // Vergelijk op .waarde: Bericht.ontvanger slaat de raw identificatiewaarde op (geen type-prefix),
        // zodat JSON-serialisatie en upstream-contracten ongewijzigd blijven.
        if (berichtInput.ontvanger != ontvangerId.waarde) {
            throw WebApplicationException(
                "Ontvanger in body komt niet overeen met X-Ontvanger header.",
                Response.Status.BAD_REQUEST,
            )
        }

        // Aanmeld Service mag alleen bestaande, actieve sessies bijwerken: als er geen
        // aggregatie heeft plaatsgevonden voor deze ontvanger, hoort er ook geen cache te zijn.
        val aggregation = awaitOrServiceUnavailable {
            berichtensessiecacheService.getAggregationStatus(ontvangerId)
        }
        if (aggregation == null) {
            throw WebApplicationException(
                "Geen actieve sessie voor deze ontvanger; bericht niet toegevoegd.",
                Response.Status.NOT_FOUND,
            )
        }

        val bericht = Bericht(
            berichtId = berichtInput.berichtId,
            afzender = berichtInput.afzender,
            ontvanger = berichtInput.ontvanger,
            onderwerp = berichtInput.onderwerp,
            tijdstip = berichtInput.tijdstip,
            magazijnId = berichtInput.magazijnId,
        )

        val result = awaitOrServiceUnavailable {
            berichtensessiecacheService.addBericht(bericht, ontvangerId)
        }

        logboekContext.status = StatusCode.OK
        return result.toResponse()
    }

    private fun requireOntvanger(ontvanger: String?): Identificatienummer {
        if (ontvanger.isNullOrBlank()) {
            throw WebApplicationException("Header 'X-Ontvanger' is verplicht.", Response.Status.BAD_REQUEST)
        }
        return Identificatienummer.fromHeader(ontvanger)
    }

    private fun requireGereedStatus(ontvanger: String?): Pair<Identificatienummer, AggregationStatus> {
        if (ontvanger.isNullOrBlank()) {
            throw WebApplicationException("Header 'X-Ontvanger' is verplicht.", Response.Status.BAD_REQUEST)
        }

        val ontvangerId = Identificatienummer.fromHeader(ontvanger)

        val aggregation = awaitOrServiceUnavailable {
            berichtensessiecacheService.getAggregationStatus(ontvangerId)
        }

        if (aggregation == null) {
            throw WebApplicationException(
                "Berichten zijn nog niet opgehaald. Roep eerst GET /api/v1/berichten/_ophalen aan.",
                Response.Status.CONFLICT,
            )
        }
        when (aggregation.status) {
            OphalenStatus.GEREED -> return ontvangerId to aggregation
            OphalenStatus.BEZIG -> throw WebApplicationException(
                "Berichten worden momenteel opgehaald. Wacht tot het ophalen is afgerond.",
                Response.Status.CONFLICT,
            )
            OphalenStatus.FOUT -> throw WebApplicationException(
                "Het ophalen van berichten is mislukt. Roep GET /api/v1/berichten/_ophalen opnieuw aan.",
                Response.Status.INTERNAL_SERVER_ERROR,
            )
        }
    }

    private fun <T> awaitOrServiceUnavailable(block: () -> io.smallrye.mutiny.Uni<T>): T {
        try {
            return block().await().atMost(TIMEOUT)
        } catch (timeoutEx: java.util.concurrent.TimeoutException) {
            // Cache-operatie hing langer dan TIMEOUT — log warn met cause zodat operations
            // niet alleen het 503-pad ziet maar ook welke await het was. Silent discard
            // verbergt prestatie-regressies in Redis.
            log.warnf(timeoutEx, "Cache-operatie overschreed timeout van %s", TIMEOUT)
            throw WebApplicationException("Cache niet bereikbaar. Probeer het later opnieuw", 503)
        } catch (e: WebApplicationException) {
            throw e
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            // Cache-deserialisatie-fout = data-integriteit-issue (verkeerde versie, corrupte hash),
            // niet "Redis onbereikbaar". 500 routeert via ProblemExceptionMapper zonder
            // de verkeerde infrastructuur-diagnose te suggereren.
            log.errorf(e, "Cache-data niet deserialiseerbaar (corruptie of schema-drift)")
            throw WebApplicationException("Cache-data niet leesbaar.", 500)
        } catch (e: CacheCorruptedException) {
            // Hash-velden ontbreken of onleesbaar → eigen data-issue, niet bereikbaarheids-issue.
            log.errorf(e, "Cache-hash corrupt")
            throw WebApplicationException("Cache-data niet leesbaar.", 500)
        } catch (e: Exception) {
            log.errorf(e, "Cache-operatie mislukt")
            throw WebApplicationException("Cache niet bereikbaar.", 503)
        }
    }

    private fun BerichtenPage.toResponse(
        aggregation: AggregationStatus?,
        afzender: String? = null,
    ): BerichtensessiecacheResponse {
        val basePath = uriInfo.baseUri.path.removeSuffix("/")
        val filterParams = if (afzender != null) {
            "&afzender=" + URLEncoder.encode(afzender, StandardCharsets.UTF_8)
        } else {
            ""
        }

        return BerichtensessiecacheResponse().apply {
            berichten = this@toResponse.berichten.map { it.toApiModel() }
            page = this@toResponse.page
            pageSize = this@toResponse.pageSize
            totalElements = this@toResponse.totalElements
            totalPages = this@toResponse.totalPages
            if (aggregation != null) {
                aggregatie = ApiAggregationStatus().apply {
                    status = ApiAggregationStatus.StatusEnum.fromString(aggregation.status.name)
                    totaalMagazijnen = aggregation.totaalMagazijnen
                    geslaagd = aggregation.geslaagd
                    mislukt = aggregation.mislukt
                }
            }
            links = PaginationLinks().apply {
                self = Link().apply { href = URI.create("$basePath/berichten?page=${this@toResponse.page}&pageSize=${this@toResponse.pageSize}$filterParams") }
                first = Link().apply { href = URI.create("$basePath/berichten?page=0&pageSize=${this@toResponse.pageSize}$filterParams") }
                if (this@toResponse.totalPages > 0) {
                    last = Link().apply { href = URI.create("$basePath/berichten?page=${this@toResponse.totalPages - 1}&pageSize=${this@toResponse.pageSize}$filterParams") }
                }
                if (this@toResponse.page > 0) {
                    prev = Link().apply { href = URI.create("$basePath/berichten?page=${this@toResponse.page - 1}&pageSize=${this@toResponse.pageSize}$filterParams") }
                }
                if (this@toResponse.page < this@toResponse.totalPages - 1) {
                    next = Link().apply { href = URI.create("$basePath/berichten?page=${this@toResponse.page + 1}&pageSize=${this@toResponse.pageSize}$filterParams") }
                }
            }
        }
    }

    private fun Bericht.toApiModel(): nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.Bericht {
        val basePath = uriInfo.baseUri.path.removeSuffix("/")
        return nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.Bericht().apply {
            berichtId = this@toApiModel.berichtId
            afzender = this@toApiModel.afzender
            ontvanger = this@toApiModel.ontvanger
            onderwerp = this@toApiModel.onderwerp
            tijdstip = this@toApiModel.tijdstip
            magazijnId = this@toApiModel.magazijnId
            status = this@toApiModel.status?.let { ApiBerichtStatus.fromValue(it.lowercase()) }
            links = BerichtLinks().apply {
                self = Link().apply { href = URI.create("$basePath/berichten/${this@toApiModel.berichtId}") }
            }
        }
    }

    private fun Bericht.toResponse(): BerichtResponse {
        val basePath = uriInfo.baseUri.path.removeSuffix("/")
        return BerichtResponse().apply {
            berichtId = this@toResponse.berichtId
            afzender = this@toResponse.afzender
            ontvanger = this@toResponse.ontvanger
            onderwerp = this@toResponse.onderwerp
            tijdstip = this@toResponse.tijdstip
            magazijnId = this@toResponse.magazijnId
            status = this@toResponse.status?.let { ApiBerichtStatus.fromValue(it.lowercase()) }
            links = BerichtLinks().apply {
                self = Link().apply { href = URI.create("$basePath/berichten/${this@toResponse.berichtId}") }
            }
        }
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(5)
    }
}
