package nl.rijksoverheid.moz.berichtenlijst.berichten

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Duration
import java.util.UUID

interface BerichtenCache {
    fun store(key: String, berichten: List<Bericht>): Uni<Void>
    fun storeAggregationStatus(key: String, status: AggregationStatus): Uni<Void>
    fun getAggregationStatus(key: String): Uni<AggregationStatus?>
    fun getPage(key: String, page: Int, pageSize: Int): Uni<BerichtenPage?>
    fun getAll(key: String): Uni<List<Bericht>>
    fun getById(berichtId: UUID): Uni<Bericht?>

    companion object {
        fun cacheKey(ontvanger: String?) = "berichtenlijst:v1:${ontvanger ?: "all"}"
        fun berichtKey(berichtId: UUID) = "bericht:v1:$berichtId"
    }
}

@ApplicationScoped
class RedisBerichtenCache(
    private val redis: ReactiveRedisDataSource,
    private val objectMapper: ObjectMapper,
) : BerichtenCache {
    private val log = Logger.getLogger(RedisBerichtenCache::class.java)

    override fun store(key: String, berichten: List<Bericht>): Uni<Void> {
        val listKey = listKey(key)
        if (berichten.isEmpty()) {
            return redis.key().del(listKey).replaceWithVoid()
        }
        val listCommands = redis.list(String::class.java)
        val keyCommands = redis.key()
        val valueCommands = redis.value(String::class.java)

        val sorted = berichten.sortedByDescending { it.tijdstip }
        val jsonValues = sorted.map { objectMapper.writeValueAsString(it) }

        return keyCommands.del(listKey)
            .chain { _ -> listCommands.rpush(listKey, *jsonValues.toTypedArray()) }
            .chain { _ -> keyCommands.expire(listKey, TTL) }
            .chain { _ ->
                // Sla elk bericht ook individueel op voor lookup by ID
                val stores = sorted.zip(jsonValues).map { (bericht, json) ->
                    val berichtKey = BerichtenCache.berichtKey(bericht.berichtId)
                    valueCommands.setex(berichtKey, TTL.seconds, json).replaceWithVoid()
                }
                Uni.join().all(stores).andFailFast().replaceWithVoid()
            }
            .invoke { _ -> log.debugf("Opgeslagen %d berichten in cache key=%s", berichten.size, key) }
            .onFailure().invoke { e -> log.errorf(e, "Redis store mislukt voor key=%s", key) }
    }

    override fun storeAggregationStatus(key: String, status: AggregationStatus): Uni<Void> {
        val statusKey = statusKey(key)
        val json = objectMapper.writeValueAsString(status)
        return redis.value(String::class.java).setex(statusKey, TTL.seconds, json)
            .replaceWithVoid()
            .onFailure().invoke { e -> log.errorf(e, "Redis storeAggregationStatus mislukt voor key=%s", key) }
    }

    override fun getAggregationStatus(key: String): Uni<AggregationStatus?> {
        val statusKey = statusKey(key)
        return redis.value(String::class.java).get(statusKey)
            .map { json -> json?.let { objectMapper.readValue(it, AggregationStatus::class.java) } }
            .onFailure().invoke { e -> log.errorf(e, "Redis getAggregationStatus mislukt voor key=%s", key) }
    }

    override fun getPage(key: String, page: Int, pageSize: Int): Uni<BerichtenPage?> {
        val listKey = listKey(key)
        val start = page.toLong() * pageSize
        val stop = start + pageSize - 1

        return redis.key().exists(listKey).chain { exists ->
            if (!exists) {
                Uni.createFrom().nullItem()
            } else {
                Uni.combine().all()
                    .unis(
                        redis.list(String::class.java).lrange(listKey, start, stop),
                        redis.list(String::class.java).llen(listKey),
                    ).asTuple()
                    .map { tuple ->
                        val jsonList = tuple.item1
                        val total = tuple.item2
                        val berichten = jsonList.map { objectMapper.readValue(it, Bericht::class.java) }
                        val totalPages = if (total == 0L) 0 else ((total + pageSize - 1) / pageSize).toInt()
                        BerichtenPage(
                            berichten = berichten,
                            page = page,
                            pageSize = pageSize,
                            totalElements = total,
                            totalPages = totalPages,
                        )
                    }
            }
        }
        .onFailure().invoke { e -> log.errorf(e, "Redis getPage mislukt voor key=%s, page=%d", key, page) }
    }

    override fun getAll(key: String): Uni<List<Bericht>> {
        val listKey = listKey(key)
        return redis.list(String::class.java).lrange(listKey, 0, -1)
            .map { jsonList -> jsonList.map { objectMapper.readValue(it, Bericht::class.java) } }
            .onFailure().invoke { e -> log.errorf(e, "Redis getAll mislukt voor key=%s", key) }
    }

    override fun getById(berichtId: UUID): Uni<Bericht?> {
        val berichtKey = BerichtenCache.berichtKey(berichtId)
        return redis.value(String::class.java).get(berichtKey)
            .map { json -> json?.let { objectMapper.readValue(it, Bericht::class.java) } }
            .onFailure().invoke { e -> log.errorf(e, "Redis getById mislukt voor berichtId=%s", berichtId) }
    }

    companion object {
        private val TTL = Duration.ofSeconds(60)
        private fun listKey(key: String) = "$key:list"
        private fun statusKey(key: String) = "$key:status"
    }
}

data class AggregationStatus(
    val status: OphalenStatus = OphalenStatus.GEREED,
    val totaalMagazijnen: Int = 0,
    val geslaagd: Int = 0,
    val mislukt: Int = 0,
) {
    init {
        require(totaalMagazijnen >= 0) { "totaalMagazijnen mag niet negatief zijn" }
        require(geslaagd >= 0) { "geslaagd mag niet negatief zijn" }
        require(mislukt >= 0) { "mislukt mag niet negatief zijn" }
        require(geslaagd + mislukt <= totaalMagazijnen) { "geslaagd + mislukt mag niet groter zijn dan totaalMagazijnen" }
    }
}

enum class OphalenStatus {
    BEZIG,
    GEREED,
}
