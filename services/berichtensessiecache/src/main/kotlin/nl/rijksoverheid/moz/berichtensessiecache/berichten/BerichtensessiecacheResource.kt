package nl.rijksoverheid.moz.berichtensessiecache.berichten

import io.opentelemetry.api.trace.StatusCode
import jakarta.inject.Inject
import jakarta.ws.rs.Path
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.berichtensessiecache.api.model.AggregationStatus as ApiAggregationStatus
import nl.rijksoverheid.moz.berichtensessiecache.api.model.BerichtLinks
import nl.rijksoverheid.moz.berichtensessiecache.api.model.BerichtResponse
import nl.rijksoverheid.moz.berichtensessiecache.api.model.BerichtensessiecacheResponse
import nl.rijksoverheid.moz.berichtensessiecache.api.model.Link
import nl.rijksoverheid.moz.berichtensessiecache.api.model.PaginationLinks
import org.jboss.logging.Logger
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

@Path("/api/v1/berichten")
class BerichtensessiecacheResource(
    private val berichtensessiecacheService: BerichtensessiecacheService,
) : nl.rijksoverheid.moz.berichtensessiecache.api.BerichtenApi {

    private val log = Logger.getLogger(BerichtensessiecacheResource::class.java)

    @Inject
    lateinit var logboekContext: LogboekContext

    @Context
    lateinit var uriInfo: UriInfo

    @Logboek(
        name = "ophalen-berichtensessiecache",
        processingActivityId = "https://register.example.com/verwerkingen/berichtensessiecache-ophalen",
    )
    override fun getBerichten(
        ontvanger: String?,
        page: Int?,
        pageSize: Int?,
        afzender: String?,
    ): BerichtensessiecacheResponse {
        ontvanger?.let {
            logboekContext.dataSubjectId = it
            logboekContext.dataSubjectType = "ontvanger"
        }

        val aggregation = awaitOrServiceUnavailable {
            berichtensessiecacheService.getAggregationStatus(ontvanger)
        }

        if (aggregation == null) {
            throw WebApplicationException(
                "Berichten zijn nog niet opgehaald. Roep eerst GET /api/v1/berichten/_ophalen aan.",
                Response.Status.CONFLICT,
            )
        }
        if (aggregation.status == OphalenStatus.BEZIG) {
            throw WebApplicationException(
                "Berichten worden momenteel opgehaald. Wacht tot het ophalen is afgerond.",
                Response.Status.CONFLICT,
            )
        }
        if (aggregation.status == OphalenStatus.FOUT) {
            throw WebApplicationException(
                "Het ophalen van berichten is mislukt. Roep GET /api/v1/berichten/_ophalen opnieuw aan.",
                Response.Status.INTERNAL_SERVER_ERROR,
            )
        }

        // TODO: afzender parameter wordt momenteel genegeerd -- filter niet actief (PoC)
        val p = page ?: 0
        val ps = pageSize ?: 20
        val result = awaitOrServiceUnavailable {
            berichtensessiecacheService.getBerichten(p, ps, ontvanger, afzender)
        }
        logboekContext.status = StatusCode.OK
        return toBerichtensessiecacheResponse(result, aggregation, ontvanger)
    }

    @Logboek(
        name = "ophalen-bericht-bij-id",
        processingActivityId = "https://register.example.com/verwerkingen/bericht-ophalen",
    )
    override fun getBerichtById(berichtId: UUID, ontvanger: String?): BerichtResponse {
        ontvanger?.let {
            logboekContext.dataSubjectId = it
            logboekContext.dataSubjectType = "ontvanger"
        }

        val aggregation = awaitOrServiceUnavailable {
            berichtensessiecacheService.getAggregationStatus(ontvanger)
        }

        if (aggregation == null) {
            throw WebApplicationException(
                "Berichten zijn nog niet opgehaald. Roep eerst GET /api/v1/berichten/_ophalen aan.",
                Response.Status.CONFLICT,
            )
        }
        if (aggregation.status == OphalenStatus.BEZIG) {
            throw WebApplicationException(
                "Berichten worden momenteel opgehaald. Wacht tot het ophalen is afgerond.",
                Response.Status.CONFLICT,
            )
        }
        if (aggregation.status == OphalenStatus.FOUT) {
            throw WebApplicationException(
                "Het ophalen van berichten is mislukt. Roep GET /api/v1/berichten/_ophalen opnieuw aan.",
                Response.Status.INTERNAL_SERVER_ERROR,
            )
        }

        val bericht = awaitOrServiceUnavailable {
            berichtensessiecacheService.getBerichtById(berichtId)
        } ?: throw WebApplicationException(
            "Bericht $berichtId niet gevonden", Response.Status.NOT_FOUND,
        )

        logboekContext.status = StatusCode.OK
        return toBerichtResponse(bericht)
    }

    @Logboek(
        name = "zoeken-berichten",
        processingActivityId = "https://register.example.com/verwerkingen/berichten-zoeken",
    )
    override fun zoekBerichten(
        q: String,
        ontvanger: String?,
        page: Int?,
        pageSize: Int?,
        afzender: String?,
    ): BerichtensessiecacheResponse {
        ontvanger?.let {
            logboekContext.dataSubjectId = it
            logboekContext.dataSubjectType = "ontvanger"
        }

        val aggregation = awaitOrServiceUnavailable {
            berichtensessiecacheService.getAggregationStatus(ontvanger)
        }

        if (aggregation == null) {
            throw WebApplicationException(
                "Berichten zijn nog niet opgehaald. Roep eerst GET /api/v1/berichten/_ophalen aan.",
                Response.Status.CONFLICT,
            )
        }
        if (aggregation.status == OphalenStatus.BEZIG) {
            throw WebApplicationException(
                "Berichten worden momenteel opgehaald. Wacht tot het ophalen is afgerond.",
                Response.Status.CONFLICT,
            )
        }
        if (aggregation.status == OphalenStatus.FOUT) {
            throw WebApplicationException(
                "Het ophalen van berichten is mislukt. Roep GET /api/v1/berichten/_ophalen opnieuw aan.",
                Response.Status.INTERNAL_SERVER_ERROR,
            )
        }

        // TODO: afzender parameter wordt momenteel genegeerd -- filter niet actief (PoC)
        val p = page ?: 0
        val ps = pageSize ?: 20
        val result = awaitOrServiceUnavailable {
            berichtensessiecacheService.zoekBerichten(q, p, ps, ontvanger, afzender)
        }
        logboekContext.status = StatusCode.OK
        return toBerichtensessiecacheResponse(result, aggregation, ontvanger)
    }

    private fun <T> awaitOrServiceUnavailable(block: () -> io.smallrye.mutiny.Uni<T>): T {
        try {
            return block().await().atMost(TIMEOUT)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw WebApplicationException("Cache niet bereikbaar. Probeer het later opnieuw", 503)
        } catch (e: WebApplicationException) {
            throw e
        } catch (e: Exception) {
            log.errorf(e, "Cache-operatie mislukt")
            throw WebApplicationException("Cache niet bereikbaar.", 503)
        }
    }

    private fun toBerichtensessiecacheResponse(
        result: BerichtenPage,
        aggregation: AggregationStatus?,
        ontvanger: String?,
    ): BerichtensessiecacheResponse {
        val response = BerichtensessiecacheResponse()
        response.berichten = result.berichten.map { toApiBericht(it) }
        response.page = result.page
        response.pageSize = result.pageSize
        response.totalElements = result.totalElements
        response.totalPages = result.totalPages
        if (aggregation != null) {
            response.aggregatie = ApiAggregationStatus().apply {
                status = ApiAggregationStatus.StatusEnum.fromString(aggregation.status.name)
                totaalMagazijnen = aggregation.totaalMagazijnen
                geslaagd = aggregation.geslaagd
                mislukt = aggregation.mislukt
            }
        }
        val basePath = uriInfo.baseUri.path.removeSuffix("/")
        val ontvangerParam = ontvanger?.let { "&ontvanger=${URLEncoder.encode(it, StandardCharsets.UTF_8)}" } ?: ""
        response.links = PaginationLinks().apply {
            self = Link().apply { href = URI.create("$basePath/berichten?page=${result.page}&pageSize=${result.pageSize}$ontvangerParam") }
            first = Link().apply { href = URI.create("$basePath/berichten?page=0&pageSize=${result.pageSize}$ontvangerParam") }
            if (result.totalPages > 0) {
                last = Link().apply { href = URI.create("$basePath/berichten?page=${result.totalPages - 1}&pageSize=${result.pageSize}$ontvangerParam") }
            }
            if (result.page > 0) {
                prev = Link().apply { href = URI.create("$basePath/berichten?page=${result.page - 1}&pageSize=${result.pageSize}$ontvangerParam") }
            }
            if (result.page < result.totalPages - 1) {
                next = Link().apply { href = URI.create("$basePath/berichten?page=${result.page + 1}&pageSize=${result.pageSize}$ontvangerParam") }
            }
        }
        return response
    }

    private fun berichtLinks(berichtId: UUID): BerichtLinks {
        val basePath = uriInfo.baseUri.path.removeSuffix("/")
        return BerichtLinks().apply {
            self = Link().apply { href = URI.create("$basePath/berichten/$berichtId") }
        }
    }

    private fun toApiBericht(bericht: Bericht): nl.rijksoverheid.moz.berichtensessiecache.api.model.Bericht {
        val apiBericht = nl.rijksoverheid.moz.berichtensessiecache.api.model.Bericht()
        apiBericht.berichtId = bericht.berichtId
        apiBericht.afzender = bericht.afzender
        apiBericht.ontvanger = bericht.ontvanger
        apiBericht.onderwerp = bericht.onderwerp
        apiBericht.tijdstip = bericht.tijdstip
        apiBericht.magazijnId = bericht.magazijnId
        apiBericht.links = berichtLinks(bericht.berichtId)
        return apiBericht
    }

    private fun toBerichtResponse(bericht: Bericht): BerichtResponse {
        val response = BerichtResponse()
        response.berichtId = bericht.berichtId
        response.afzender = bericht.afzender
        response.ontvanger = bericht.ontvanger
        response.onderwerp = bericht.onderwerp
        response.tijdstip = bericht.tijdstip
        response.magazijnId = bericht.magazijnId
        response.links = berichtLinks(bericht.berichtId)
        return response
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(5)
    }
}
