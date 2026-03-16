package nl.rijksoverheid.moz.berichtensessiecache.berichten

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
    fun trySetAggregationStatus(key: String, status: AggregationStatus): Uni<Boolean>
    fun getAggregationStatus(key: String): Uni<AggregationStatus?>
    fun getPage(key: String, page: Int, pageSize: Int): Uni<BerichtenPage?>
    fun getAll(key: String): Uni<List<Bericht>>
    fun getById(berichtId: UUID): Uni<Bericht?>

    companion object {
        fun cacheKey(ontvanger: String?) = "berichtensessiecache:v1:${ontvanger ?: "all"}"
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

        val sorted = berichten.sortedByDescending { it.tijdstip }
        val jsonValues = sorted.map { objectMapper.writeValueAsString(it) }

        // Gebruik een Redis transaction (MULTI/EXEC) zodat alle commands in één round-trip gaan
        // i.p.v. N+3 losse round-trips (DEL + RPUSH + EXPIRE + N × SETEX)
        return redis.withTransaction { tx ->
            val txList = tx.list(String::class.java)
            val txKey = tx.key()
            val txValue = tx.value(String::class.java)

            txKey.del(listKey)
                .chain { _ -> txList.rpush(listKey, *jsonValues.toTypedArray()) }
                .chain { _ -> txKey.expire(listKey, TTL) }
                .chain { _ ->
                    // Sla elk bericht ook individueel op voor lookup by ID
                    val stores = sorted.zip(jsonValues).map { (bericht, json) ->
                        val berichtKey = BerichtenCache.berichtKey(bericht.berichtId)
                        txValue.setex(berichtKey, TTL.seconds, json).replaceWithVoid()
                    }
                    Uni.join().all(stores).andFailFast().replaceWithVoid()
                }
        }
            .replaceWithVoid()
            .invoke { _ -> log.debugf("Opgeslagen %d berichten in cache key=%s", berichten.size, key) }
            .onFailure().invoke { e -> log.errorf(e, "Redis store mislukt voor key=%s", key) }
    }

    override fun storeAggregationStatus(key: String, status: AggregationStatus): Uni<Void> {
        val statusKey = statusKey(key)
        val json = objectMapper.writeValueAsString(status)
        return redis.value(String::class.java).setex(statusKey, TTL.seconds, json)
            .chain { _ -> redis.key().del(lockKey(key)) }
            .replaceWithVoid()
            .onFailure().invoke { e -> log.errorf(e, "Redis storeAggregationStatus mislukt voor key=%s", key) }
    }

    override fun trySetAggregationStatus(key: String, status: AggregationStatus): Uni<Boolean> {
        val lockKey = lockKey(key)
        val statusKey = statusKey(key)
        val json = objectMapper.writeValueAsString(status)
        return redis.value(String::class.java).setnx(lockKey, "1")
            .chain { wasSet ->
                if (wasSet) {
                    redis.key().expire(lockKey, TTL)
                        .chain { _ -> redis.value(String::class.java).setex(statusKey, TTL.seconds, json) }
                        .replaceWith(true)
                } else {
                    Uni.createFrom().item(false)
                }
            }
            .onFailure().invoke { e -> log.errorf(e, "Redis trySetAggregationStatus mislukt voor key=%s", key) }
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

        // LRANGE op een niet-bestaande key retourneert een lege lijst, LLEN retourneert 0.
        // Daarom is een aparte EXISTS check overbodig (bespaart één Redis round-trip).
        return Uni.combine().all()
            .unis(
                redis.list(String::class.java).lrange(listKey, start, stop),
                redis.list(String::class.java).llen(listKey),
            ).asTuple()
            .map { tuple ->
                val jsonList = tuple.item1
                val total = tuple.item2
                if (total == 0L && jsonList.isEmpty()) {
                    null
                } else {
                    val berichten = jsonList.map { objectMapper.readValue(it, Bericht::class.java) }
                    val totalPages = ((total + pageSize - 1) / pageSize).toInt()
                    BerichtenPage(
                        berichten = berichten,
                        page = page,
                        pageSize = pageSize,
                        totalElements = total,
                        totalPages = totalPages,
                    )
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
        private fun lockKey(key: String) = "$key:lock"
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
