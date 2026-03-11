package nl.rijksoverheid.moz.berichtenlijst.berichten

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Duration

interface BerichtenCache {
    fun store(key: String, berichten: List<Bericht>): Uni<Void>
    fun storeAggregationStatus(key: String, status: AggregationStatus): Uni<Void>
    fun getAggregationStatus(key: String): Uni<AggregationStatus?>
    fun getPage(key: String, page: Int, pageSize: Int): Uni<BerichtenPage?>

    companion object {
        fun cacheKey(ontvanger: String?) = "berichtenlijst:v1:${ontvanger ?: "all"}"
        fun searchCacheKey(q: String, ontvanger: String?) = "berichtenlijst:v1:zoeken:$q:${ontvanger ?: "all"}"
    }
}

@ApplicationScoped
class RedisBerichtenCache(
    private val redis: ReactiveRedisDataSource,
    private val objectMapper: ObjectMapper,
) : BerichtenCache {
    private val log = Logger.getLogger(RedisBerichtenCache::class.java)

    override fun store(key: String, berichten: List<Bericht>): Uni<Void> {
        if (berichten.isEmpty()) {
            return Uni.createFrom().voidItem()
        }
        val listKey = listKey(key)
        val listCommands = redis.list(String::class.java)
        val keyCommands = redis.key()

        val jsonValues = berichten
            .sortedByDescending { it.tijdstip }
            .map { objectMapper.writeValueAsString(it) }

        return keyCommands.del(listKey)
            .chain { _ -> listCommands.rpush(listKey, *jsonValues.toTypedArray()) }
            .chain { _ -> keyCommands.expire(listKey, TTL) }
            .replaceWithVoid()
            .invoke { _ -> log.debugf("Opgeslagen %d berichten in cache key=%s", berichten.size, key) }
    }

    override fun storeAggregationStatus(key: String, status: AggregationStatus): Uni<Void> {
        val statusKey = statusKey(key)
        val json = objectMapper.writeValueAsString(status)
        return redis.value(String::class.java).setex(statusKey, TTL.seconds, json)
            .replaceWithVoid()
    }

    override fun getAggregationStatus(key: String): Uni<AggregationStatus?> {
        val statusKey = statusKey(key)
        return redis.value(String::class.java).get(statusKey)
            .map { json -> json?.let { objectMapper.readValue(it, AggregationStatus::class.java) } }
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
)

enum class OphalenStatus {
    BEZIG,
    GEREED,
}
