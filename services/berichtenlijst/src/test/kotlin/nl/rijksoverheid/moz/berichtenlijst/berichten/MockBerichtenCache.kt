package nl.rijksoverheid.moz.berichtenlijst.berichten

import io.quarkus.test.Mock
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap

@Mock
@ApplicationScoped
class MockBerichtenCache : BerichtenCache {

    private val lists = ConcurrentHashMap<String, List<Bericht>>()
    private val statuses = ConcurrentHashMap<String, AggregationStatus>()

    override fun store(key: String, berichten: List<Bericht>): Uni<Void> {
        val sorted = berichten.sortedByDescending { it.tijdstip }
        lists["$key:list"] = sorted
        return Uni.createFrom().voidItem()
    }

    override fun storeAggregationStatus(key: String, status: AggregationStatus): Uni<Void> {
        statuses["$key:status"] = status
        return Uni.createFrom().voidItem()
    }

    override fun getAggregationStatus(key: String): Uni<AggregationStatus?> {
        return Uni.createFrom().item(statuses["$key:status"])
    }

    override fun getAll(key: String): Uni<List<Bericht>> {
        return Uni.createFrom().item(lists["$key:list"] ?: emptyList())
    }

    override fun getPage(key: String, page: Int, pageSize: Int): Uni<BerichtenPage?> {
        val berichten = lists["$key:list"] ?: return Uni.createFrom().nullItem()
        val start = page * pageSize
        val end = minOf(start + pageSize, berichten.size)
        val slice = if (start < berichten.size) berichten.subList(start, end) else emptyList()
        val totalPages = if (berichten.isEmpty()) 0 else (berichten.size + pageSize - 1) / pageSize
        return Uni.createFrom().item(
            BerichtenPage(
                berichten = slice,
                page = page,
                pageSize = pageSize,
                totalElements = berichten.size.toLong(),
                totalPages = totalPages,
            )
        )
    }
}
