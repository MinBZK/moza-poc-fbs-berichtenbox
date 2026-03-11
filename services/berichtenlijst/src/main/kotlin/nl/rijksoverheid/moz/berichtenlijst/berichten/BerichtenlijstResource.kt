package nl.rijksoverheid.moz.berichtenlijst.berichten

import io.smallrye.common.annotation.Blocking
import jakarta.ws.rs.Path
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.berichtenlijst.api.model.AggregationStatus as ApiAggregationStatus
import nl.rijksoverheid.moz.berichtenlijst.api.model.BerichtLinks
import nl.rijksoverheid.moz.berichtenlijst.api.model.BerichtResponse
import nl.rijksoverheid.moz.berichtenlijst.api.model.BerichtenlijstResponse
import nl.rijksoverheid.moz.berichtenlijst.api.model.Link
import nl.rijksoverheid.moz.berichtenlijst.api.model.PaginationLinks
import org.jboss.logging.Logger
import java.net.URI
import java.time.Duration
import java.util.UUID

@Path("/api/v1/berichten")
class BerichtenlijstResource(
    private val berichtenlijstService: BerichtenlijstService,
) : nl.rijksoverheid.moz.berichtenlijst.api.BerichtenApi {

    private val log = Logger.getLogger(BerichtenlijstResource::class.java)

    override fun getBerichten(
        page: Int?,
        pageSize: Int?,
        ontvanger: String?,
        afzender: String?,
    ): BerichtenlijstResponse {
        val aggregation = berichtenlijstService.getAggregationStatus(ontvanger)
            .await().atMost(TIMEOUT)

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

        // TODO: Implementeer filtering op afzender (PoC)
        val p = page ?: 0
        val ps = pageSize ?: 20
        val result = berichtenlijstService.getBerichten(p, ps, ontvanger, afzender)
            .await().atMost(TIMEOUT)
        return toBerichtenlijstResponse(result, aggregation, ontvanger)
    }

    // getBerichtById is blocking: het bevraagt magazijnen synchroon in een loop
    @Blocking
    override fun getBerichtById(berichtId: UUID): BerichtResponse {
        val bericht = berichtenlijstService.getBerichtById(berichtId)
            ?: throw WebApplicationException(Response.Status.NOT_FOUND)
        return toBerichtResponse(bericht)
    }

    override fun zoekBerichten(
        q: String,
        page: Int?,
        pageSize: Int?,
        ontvanger: String?,
        afzender: String?,
    ): BerichtenlijstResponse {
        // TODO: Implementeer filtering op afzender (PoC)
        val p = page ?: 0
        val ps = pageSize ?: 20
        val result = berichtenlijstService.zoekBerichten(q, p, ps, ontvanger, afzender)
            .await().atMost(TIMEOUT)
        return toBerichtenlijstResponse(result, null, ontvanger)
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
        val ontvangerParam = ontvanger?.let { "&ontvanger=$it" } ?: ""
        response.links = PaginationLinks().apply {
            self = Link().apply { href = URI.create("/api/v1/berichten?page=${result.page}&pageSize=${result.pageSize}$ontvangerParam") }
            first = Link().apply { href = URI.create("/api/v1/berichten?page=0&pageSize=${result.pageSize}$ontvangerParam") }
            if (result.totalPages > 0) {
                last = Link().apply { href = URI.create("/api/v1/berichten?page=${result.totalPages - 1}&pageSize=${result.pageSize}$ontvangerParam") }
            }
            if (result.page > 0) {
                prev = Link().apply { href = URI.create("/api/v1/berichten?page=${result.page - 1}&pageSize=${result.pageSize}$ontvangerParam") }
            }
            if (result.page < result.totalPages - 1) {
                next = Link().apply { href = URI.create("/api/v1/berichten?page=${result.page + 1}&pageSize=${result.pageSize}$ontvangerParam") }
            }
        }
        return response
    }

    private fun toApiBericht(bericht: Bericht): nl.rijksoverheid.moz.berichtenlijst.api.model.Bericht {
        val apiBericht = nl.rijksoverheid.moz.berichtenlijst.api.model.Bericht()
        apiBericht.berichtId = bericht.berichtId
        apiBericht.afzender = bericht.afzender
        apiBericht.ontvanger = bericht.ontvanger
        apiBericht.onderwerp = bericht.onderwerp
        apiBericht.tijdstip = bericht.tijdstip
        apiBericht.magazijnId = bericht.magazijnId
        apiBericht.links = BerichtLinks().apply {
            self = Link().apply { href = URI.create("/api/v1/berichten/${bericht.berichtId}") }
        }
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
        response.links = BerichtLinks().apply {
            self = Link().apply { href = URI.create("/api/v1/berichten/${bericht.berichtId}") }
        }
        return response
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(5)
    }
}
