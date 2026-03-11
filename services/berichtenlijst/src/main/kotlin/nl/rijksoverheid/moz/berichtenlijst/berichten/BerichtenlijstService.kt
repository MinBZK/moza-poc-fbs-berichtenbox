package nl.rijksoverheid.moz.berichtenlijst.berichten

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.berichtenlijst.magazijn.MagazijnClientFactory
import org.jboss.logging.Logger
import java.util.UUID

@ApplicationScoped
class BerichtenlijstService(
    private val berichtenCache: BerichtenCache,
    private val clientFactory: MagazijnClientFactory,
) {
    private val log = Logger.getLogger(BerichtenlijstService::class.java)

    fun getBerichten(page: Int, pageSize: Int, ontvanger: String?, afzender: String?): Uni<BerichtenPage> {
        log.debugf("Ophalen berichten uit cache: page=%d, pageSize=%d, ontvanger=%s", page, pageSize, ontvanger)
        val key = BerichtenCache.cacheKey(ontvanger)
        return berichtenCache.getPage(key, page, pageSize)
            .map { it ?: BerichtenPage(emptyList(), page, pageSize, 0L, 0) }
    }

    fun getAggregationStatus(ontvanger: String?): Uni<AggregationStatus?> {
        val key = BerichtenCache.cacheKey(ontvanger)
        return berichtenCache.getAggregationStatus(key)
    }

    fun getBerichtById(berichtId: UUID): Bericht? {
        log.debugf("Ophalen bericht: %s", berichtId)
        val clients = clientFactory.getAllClients()
        var heeftSuccessvolAntwoord = false
        for ((magazijnId, client) in clients) {
            try {
                val bericht = client.getBerichtById(berichtId.toString())
                heeftSuccessvolAntwoord = true
                if (bericht != null) return bericht
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                log.warnf(e, "Magazijn %s gaf fout voor bericht %s", magazijnId, berichtId)
            }
        }
        if (!heeftSuccessvolAntwoord) {
            throw WebApplicationException("Geen magazijn bereikbaar", 502)
        }
        return null
    }

    fun zoekBerichten(q: String, page: Int, pageSize: Int, ontvanger: String?, afzender: String?): Uni<BerichtenPage> {
        log.debugf("Zoeken berichten uit cache: q=%s, page=%d, pageSize=%d", q, page, pageSize)
        val key = BerichtenCache.cacheKey(ontvanger)
        return berichtenCache.getAll(key)
            .map { berichten ->
                val gefilterd = berichten.filter {
                    it.onderwerp.contains(q, ignoreCase = true) ||
                        it.afzender.contains(q, ignoreCase = true)
                }
                val start = page * pageSize
                val slice = gefilterd.drop(start).take(pageSize)
                val totalPages = if (gefilterd.isEmpty()) 0 else (gefilterd.size + pageSize - 1) / pageSize
                BerichtenPage(slice, page, pageSize, gefilterd.size.toLong(), totalPages)
            }
    }
}

data class BerichtenPage(
    val berichten: List<Bericht>,
    val page: Int,
    val pageSize: Int,
    val totalElements: Long,
    val totalPages: Int,
)
