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
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.BerichtSamenvatting as ApiBerichtSamenvatting
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.BerichtStatus as ApiBerichtStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.BerichtStatusUpdate
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.BerichtensessiecacheResponse
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.BijlageSamenvatting as ApiBijlageSamenvatting
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.Link
import nl.rijksoverheid.moz.fbs.berichtensessiecache.api.model.PaginationLinks
import org.jboss.logging.Logger
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
    override fun updateBerichtMetadata(
        berichtId: UUID,
        xOntvanger: String?,
        berichtStatusUpdate: BerichtStatusUpdate,
    ): BerichtResponse {
        logboekContext.dataSubjectId = berichtId.toString()
        logboekContext.dataSubjectType = "berichtId"

        val ontvangerId = requireOntvanger(xOntvanger)

        // Onbekende status-enum komt als null binnen (Jackson); zonder deze check zou een lege patch een no-op succes lijken.
        if (berichtStatusUpdate.status == null && berichtStatusUpdate.map == null) {
            throw WebApplicationException(
                "Minimaal één van 'status' of 'map' is vereist (geen geldige waarde meegegeven).",
                Response.Status.BAD_REQUEST,
            )
        }

        val nieuweStatus = berichtStatusUpdate.status?.toString()
        val nieuweMap = berichtStatusUpdate.map

        val bericht = awaitOrServiceUnavailable {
            berichtensessiecacheService.updateBerichtMetadata(berichtId, ontvangerId, nieuweStatus, nieuweMap)
        } ?: throw WebApplicationException(
            "Bericht niet gevonden", Response.Status.NOT_FOUND,
        )

        logboekContext.status = StatusCode.OK
        return bericht.toResponse()
    }

    @Logboek(
        name = "verwijderen-bericht",
        processingActivityId = "https://register.example.com/verwerkingen/bericht-verwijderen",
    )
    override fun verwijderBericht(berichtId: UUID, xOntvanger: String?) {
        logboekContext.dataSubjectId = berichtId.toString()
        logboekContext.dataSubjectType = "berichtId"

        val ontvanger = requireOntvanger(xOntvanger)

        // Idempotent: "niet in cache" is geen fout (TTL kan verlopen zijn, of een andere
        // instance heeft de dual-write-invalidate al uitgevoerd). Resource gooit dus geen
        // 404 — de delete-implementatie negeert ontbrekende entries stil.
        awaitOrServiceUnavailable {
            berichtensessiecacheService.verwijderBericht(berichtId, ontvanger)
        }

        logboekContext.status = StatusCode.OK
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
            inhoud = berichtInput.inhoud,
            publicatietijdstip = berichtInput.publicatietijdstip,
            magazijnId = berichtInput.magazijnId,
            aantalBijlagen = berichtInput.aantalBijlagen,
            bijlagen = berichtInput.bijlagen.orEmpty().map { BijlageSamenvatting(it.bijlageId, it.naam) },
            map = berichtInput.map,
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
        } catch (e: java.util.concurrent.TimeoutException) {
            // Cache-operatie hing langer dan TIMEOUT — log warn met cause zodat operations
            // niet alleen het 503-pad ziet maar ook welke await het was. Silent discard
            // verbergt prestatie-regressies in Redis.
            log.warnf(e, "Cache-operatie overschreed timeout van %s; 503 naar client", TIMEOUT)
            throw WebApplicationException("Cache niet bereikbaar. Probeer het later opnieuw", 503)
        } catch (e: CacheContentieException) {
            // Transiente schrijf-contentie: bron logt al op warn. Retriable 503
            // i.p.v. de misleidende 404 die een null-resultaat zou opleveren.
            throw WebApplicationException("Cache tijdelijk niet bij te werken. Probeer het later opnieuw", 503)
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
            berichten = this@toResponse.berichten.map { it.toApiSamenvatting() }
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
                self = Link().apply { href = "$basePath/berichten?page=${this@toResponse.page}&pageSize=${this@toResponse.pageSize}$filterParams" }
                first = Link().apply { href = "$basePath/berichten?page=0&pageSize=${this@toResponse.pageSize}$filterParams" }
                if (this@toResponse.totalPages > 0) {
                    last = Link().apply { href = "$basePath/berichten?page=${this@toResponse.totalPages - 1}&pageSize=${this@toResponse.pageSize}$filterParams" }
                }
                if (this@toResponse.page > 0) {
                    prev = Link().apply { href = "$basePath/berichten?page=${this@toResponse.page - 1}&pageSize=${this@toResponse.pageSize}$filterParams" }
                }
                if (this@toResponse.page < this@toResponse.totalPages - 1) {
                    next = Link().apply { href = "$basePath/berichten?page=${this@toResponse.page + 1}&pageSize=${this@toResponse.pageSize}$filterParams" }
                }
            }
        }
    }

    private fun BerichtSamenvatting.toApiSamenvatting(): ApiBerichtSamenvatting {
        val basePath = uriInfo.baseUri.path.removeSuffix("/")
        return ApiBerichtSamenvatting().apply {
            berichtId = this@toApiSamenvatting.berichtId
            afzender = this@toApiSamenvatting.afzender
            ontvanger = this@toApiSamenvatting.ontvanger
            onderwerp = this@toApiSamenvatting.onderwerp
            publicatietijdstip = this@toApiSamenvatting.publicatietijdstip
            magazijnId = this@toApiSamenvatting.magazijnId
            aantalBijlagen = this@toApiSamenvatting.aantalBijlagen
            map = this@toApiSamenvatting.map
            status = this@toApiSamenvatting.status?.let { ApiBerichtStatus.fromValue(it.wire) }
            links = BerichtLinks().apply {
                self = Link().apply { href = "$basePath/berichten/${this@toApiSamenvatting.berichtId}" }
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
            inhoud = this@toResponse.inhoud
            publicatietijdstip = this@toResponse.publicatietijdstip
            magazijnId = this@toResponse.magazijnId
            aantalBijlagen = this@toResponse.aantalBijlagen
            bijlagen = this@toResponse.bijlagen.map { it.toApiModel() }
            map = this@toResponse.map
            status = this@toResponse.status?.let { ApiBerichtStatus.fromValue(it.wire) }
            links = BerichtLinks().apply {
                self = Link().apply { href = "$basePath/berichten/${this@toResponse.berichtId}" }
            }
        }
    }

    private fun BijlageSamenvatting.toApiModel(): ApiBijlageSamenvatting =
        ApiBijlageSamenvatting().apply {
            bijlageId = this@toApiModel.bijlageId
            naam = this@toApiModel.naam
        }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(5)
    }
}
