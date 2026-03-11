package nl.rijksoverheid.moz.berichtenlijst.berichten

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
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
        // Fan-out naar alle magazijnen
        val clients = clientFactory.getAllClients()
        for ((_, client) in clients) {
            try {
                val bericht = client.getBerichtById(berichtId.toString())
                if (bericht != null) return bericht
            } catch (e: Exception) {
                log.debugf(e, "Magazijn gaf fout voor bericht %s", berichtId)
            }
        }
        return null
    }

    fun zoekBerichten(q: String, page: Int, pageSize: Int, ontvanger: String?, afzender: String?): Uni<BerichtenPage> {
        log.debugf("Zoeken berichten uit cache: q=%s, page=%d, pageSize=%d", q, page, pageSize)
        val key = BerichtenCache.searchCacheKey(q, ontvanger)
        return berichtenCache.getPage(key, page, pageSize)
            .map { it ?: BerichtenPage(emptyList(), page, pageSize, 0L, 0) }
    }
}

data class BerichtenPage(
    val berichten: List<Bericht>,
    val page: Int,
    val pageSize: Int,
    val totalElements: Long,
    val totalPages: Int,
)
