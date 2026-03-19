package nl.rijksoverheid.moz.berichtensessiecache.berichten

import io.quarkus.test.Mock
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Mock
@ApplicationScoped
class MockBerichtenCache : BerichtenCache {

    private val lists = ConcurrentHashMap<String, List<Bericht>>()
    private val statuses = ConcurrentHashMap<String, AggregationStatus>()
    private val locks = ConcurrentHashMap.newKeySet<String>()
    private val byId = ConcurrentHashMap<UUID, Bericht>()

    fun clear() {
        lists.clear()
        statuses.clear()
        locks.clear()
        byId.clear()
    }

    fun simuleerBezig(key: String) {
        locks.add("$key:lock")
        statuses["$key:status"] = AggregationStatus(status = OphalenStatus.BEZIG, totaalMagazijnen = 2)
    }

    override fun store(key: String, berichten: List<Bericht>): Uni<Void> {
        val sorted = berichten.sortedByDescending { it.tijdstip }
        lists["$key:list"] = sorted
        berichten.forEach { byId[it.berichtId] = it }
        return Uni.createFrom().voidItem()
    }

    override fun storeAggregationStatus(key: String, status: AggregationStatus): Uni<Void> {
        statuses["$key:status"] = status
        locks.remove("$key:lock")
        return Uni.createFrom().voidItem()
    }

    override fun trySetAggregationStatus(key: String, status: AggregationStatus): Uni<Boolean> {
        val lockAcquired = locks.add("$key:lock")
        if (lockAcquired) {
            statuses["$key:status"] = status
        }
        return Uni.createFrom().item(lockAcquired)
    }

    override fun getAggregationStatus(key: String): Uni<AggregationStatus?> {
        return Uni.createFrom().item(statuses["$key:status"])
    }

    override fun search(ontvanger: String, q: String, page: Int, pageSize: Int): Uni<BerichtenPage> {
        val key = BerichtenCache.cacheKey(ontvanger)
        val berichten = lists["$key:list"] ?: emptyList()
        val gefilterd = berichten.filter {
            it.onderwerp.contains(q, ignoreCase = true) ||
                it.afzender.contains(q, ignoreCase = true)
        }
        val start = page * pageSize
        val slice = gefilterd.drop(start).take(pageSize)
        val totalPages = if (gefilterd.isEmpty()) 0 else (gefilterd.size + pageSize - 1) / pageSize
        return Uni.createFrom().item(BerichtenPage(slice, page, pageSize, gefilterd.size.toLong(), totalPages))
    }

    override fun getById(berichtId: UUID, ontvanger: String): Uni<Bericht?> {
        val bericht = byId[berichtId]
        return Uni.createFrom().item(if (bericht?.ontvanger == ontvanger) bericht else null)
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
