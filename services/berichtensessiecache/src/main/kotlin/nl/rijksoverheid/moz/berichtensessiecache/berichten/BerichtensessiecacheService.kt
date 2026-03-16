package nl.rijksoverheid.moz.berichtensessiecache.berichten

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.util.UUID

@ApplicationScoped
class BerichtensessiecacheService(
    private val berichtenCache: BerichtenCache,
) {
    private val log = Logger.getLogger(BerichtensessiecacheService::class.java)

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

    fun getBerichtById(berichtId: UUID): Uni<Bericht?> {
        log.debugf("Ophalen bericht uit cache: %s", berichtId)
        return berichtenCache.getById(berichtId)
    }

    fun zoekBerichten(q: String, page: Int, pageSize: Int, ontvanger: String?, afzender: String?): Uni<BerichtenPage> {
        log.debugf("Zoeken berichten via RediSearch: q=%s, page=%d, pageSize=%d", q, page, pageSize)
        return berichtenCache.search(ontvanger, q, page, pageSize)
    }
}

data class BerichtenPage(
    val berichten: List<Bericht>,
    val page: Int,
    val pageSize: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    init {
        require(page >= 0) { "page mag niet negatief zijn" }
        require(pageSize > 0) { "pageSize moet positief zijn" }
        require(totalElements >= 0) { "totalElements mag niet negatief zijn" }
        require(totalPages >= 0) { "totalPages mag niet negatief zijn" }
    }
}
