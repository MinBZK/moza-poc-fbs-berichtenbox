package nl.rijksoverheid.moz.berichtenlijst.berichten

import io.opentelemetry.api.trace.StatusCode
import io.smallrye.common.annotation.Blocking
import jakarta.inject.Inject
import jakarta.ws.rs.Path
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.berichtenlijst.api.model.AggregationStatus as ApiAggregationStatus
import nl.rijksoverheid.moz.berichtenlijst.api.model.BerichtLinks
import nl.rijksoverheid.moz.berichtenlijst.api.model.BerichtResponse
import nl.rijksoverheid.moz.berichtenlijst.api.model.BerichtenlijstResponse
import nl.rijksoverheid.moz.berichtenlijst.api.model.Link
import nl.rijksoverheid.moz.berichtenlijst.api.model.PaginationLinks
import org.jboss.logging.Logger
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

@Path("/api/v1/berichten")
class BerichtenlijstResource(
    private val berichtenlijstService: BerichtenlijstService,
) : nl.rijksoverheid.moz.berichtenlijst.api.BerichtenApi {

    private val log = Logger.getLogger(BerichtenlijstResource::class.java)

    @Inject
    lateinit var logboekContext: LogboekContext

    @Context
    lateinit var uriInfo: UriInfo

    @Logboek(
        name = "ophalen-berichtenlijst",
        processingActivityId = "https://register.example.com/verwerkingen/berichtenlijst-ophalen",
    )
    override fun getBerichten(
        ontvanger: String?,
        page: Int?,
        pageSize: Int?,
        afzender: String?,
    ): BerichtenlijstResponse {
        ontvanger?.let {
            logboekContext.dataSubjectId = it
            logboekContext.dataSubjectType = "ontvanger"
        }

        val aggregation = awaitOrServiceUnavailable {
            berichtenlijstService.getAggregationStatus(ontvanger)
        }

        if (aggregation == null) {
            throw WebApplicationException(
                "Berichten zijn nog niet opgehaald. Roep eerst GET /api/v1/berichten/ophalen aan.",
                Response.Status.CONFLICT,
            )
        }
        if (aggregation.status == OphalenStatus.BEZIG) {
            throw WebApplicationException(
                "Berichten worden momenteel opgehaald. Wacht tot het ophalen is afgerond.",
                Response.Status.CONFLICT,
            )
        }

        // TODO: afzender parameter wordt momenteel genegeerd -- filter niet actief (PoC)
        val p = page ?: 0
        val ps = pageSize ?: 20
        val result = awaitOrServiceUnavailable {
            berichtenlijstService.getBerichten(p, ps, ontvanger, afzender)
        }
        logboekContext.status = StatusCode.OK
        return toBerichtenlijstResponse(result, aggregation, ontvanger)
    }

    // @Blocking vereist: de service bevraagt magazijnen synchroon via REST clients (zie BerichtenlijstService.getBerichtById)
    @Blocking
    @Logboek(
        name = "ophalen-bericht-bij-id",
        processingActivityId = "https://register.example.com/verwerkingen/bericht-ophalen",
    )
    override fun getBerichtById(berichtId: UUID): BerichtResponse {
        logboekContext.dataSubjectId = berichtId.toString()
        logboekContext.dataSubjectType = "berichtId"

        return when (val result = berichtenlijstService.getBerichtById(berichtId)) {
            is BerichtLookupResult.Found -> {
                logboekContext.status = StatusCode.OK
                toBerichtResponse(result.bericht)
            }
            is BerichtLookupResult.NotFound -> throw WebApplicationException(
                "Bericht $berichtId niet gevonden", Response.Status.NOT_FOUND,
            )
            is BerichtLookupResult.AllMagazijnenFailed -> throw WebApplicationException(
                "Geen magazijn bereikbaar voor bericht $berichtId. Probeer het later opnieuw.", 502,
            )
        }
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
    ): BerichtenlijstResponse {
        ontvanger?.let {
            logboekContext.dataSubjectId = it
            logboekContext.dataSubjectType = "ontvanger"
        }

        val aggregation = awaitOrServiceUnavailable {
            berichtenlijstService.getAggregationStatus(ontvanger)
        }

        if (aggregation == null) {
            throw WebApplicationException(
                "Berichten zijn nog niet opgehaald. Roep eerst GET /api/v1/berichten/ophalen aan.",
                Response.Status.CONFLICT,
            )
        }
        if (aggregation.status == OphalenStatus.BEZIG) {
            throw WebApplicationException(
                "Berichten worden momenteel opgehaald. Wacht tot het ophalen is afgerond.",
                Response.Status.CONFLICT,
            )
        }

        // TODO: afzender parameter wordt momenteel genegeerd -- filter niet actief (PoC)
        val p = page ?: 0
        val ps = pageSize ?: 20
        val result = awaitOrServiceUnavailable {
            berichtenlijstService.zoekBerichten(q, p, ps, ontvanger, afzender)
        }
        logboekContext.status = StatusCode.OK
        return toBerichtenlijstResponse(result, aggregation, ontvanger)
    }

    private fun <T> awaitOrServiceUnavailable(block: () -> io.smallrye.mutiny.Uni<T>): T {
        try {
            return block().await().atMost(TIMEOUT)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw WebApplicationException("Cache niet bereikbaar. Probeer het later opnieuw.", 503)
        }
    }

    private fun toBerichtenlijstResponse(
        result: BerichtenPage,
        aggregation: AggregationStatus?,
        ontvanger: String?,
    ): BerichtenlijstResponse {
        val response = BerichtenlijstResponse()
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

    private fun toApiBericht(bericht: Bericht): nl.rijksoverheid.moz.berichtenlijst.api.model.Bericht {
        val apiBericht = nl.rijksoverheid.moz.berichtenlijst.api.model.Bericht()
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
