package nl.rijksoverheid.moz.berichtensessiecache.berichten

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.search.CreateArgs
import io.quarkus.redis.datasource.search.FieldType
import io.quarkus.redis.datasource.search.QueryArgs
import io.smallrye.mutiny.Uni
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface BerichtenCache {
    fun store(key: String, berichten: List<Bericht>): Uni<Void>
    fun storeAggregationStatus(key: String, status: AggregationStatus): Uni<Void>
    fun trySetAggregationStatus(key: String, status: AggregationStatus): Uni<Boolean>
    fun getAggregationStatus(key: String): Uni<AggregationStatus?>
    fun getPage(key: String, page: Int, pageSize: Int): Uni<BerichtenPage?>
    fun search(ontvanger: String?, q: String, page: Int, pageSize: Int): Uni<BerichtenPage>
    fun getById(berichtId: UUID, ontvanger: String): Uni<Bericht?>

    companion object {
        fun cacheKey(ontvanger: String?) = "berichtensessiecache:v1:${ontvanger ?: "all"}"
        fun berichtKey(berichtId: UUID) = "bericht:v1:$berichtId"
        const val BERICHT_PREFIX = "bericht:v1:"
        const val SEARCH_INDEX = "berichten-idx"
    }
}

@ApplicationScoped
class RedisBerichtenCache(
    private val redis: ReactiveRedisDataSource,
    private val objectMapper: ObjectMapper,
) : BerichtenCache {
    private val log = Logger.getLogger(RedisBerichtenCache::class.java)

    @PostConstruct
    fun init() {
        try {
            val args = CreateArgs()
                .onHash()
                .prefixes(BerichtenCache.BERICHT_PREFIX)
                .indexedField("onderwerp", FieldType.TEXT)
                .indexedField("afzender", FieldType.TEXT)
                .indexedField("ontvanger", FieldType.TAG)
            redis.search().ftCreate(BerichtenCache.SEARCH_INDEX, args)
                .await().atMost(Duration.ofSeconds(5))
            log.infof("RediSearch index '%s' aangemaakt", BerichtenCache.SEARCH_INDEX)
        } catch (e: Exception) {
            if (e.message?.contains("Index already exists") == true) {
                log.debugf("RediSearch index '%s' bestaat al", BerichtenCache.SEARCH_INDEX)
            } else {
                log.errorf(e, "Kon RediSearch index '%s' niet aanmaken", BerichtenCache.SEARCH_INDEX)
            }
        }
    }

    override fun store(key: String, berichten: List<Bericht>): Uni<Void> {
        val listKey = listKey(key)
        if (berichten.isEmpty()) {
            return redis.key().del(listKey).replaceWithVoid()
        }

        val sorted = berichten.sortedByDescending { it.tijdstip }
        val jsonValues = sorted.map { objectMapper.writeValueAsString(it) }

        // Gebruik een Redis transaction (MULTI/EXEC) zodat alle commands in één round-trip gaan
        return redis.withTransaction { tx ->
            val txList = tx.list(String::class.java)
            val txKey = tx.key()
            val txHash = tx.hash(String::class.java)

            txKey.del(listKey)
                .chain { _ -> txList.rpush(listKey, *jsonValues.toTypedArray()) }
                .chain { _ -> txKey.expire(listKey, TTL) }
                .chain { _ ->
                    // Sla elk bericht op als Hash voor RediSearch full-text index en lookup by ID
                    val stores = sorted.map { bericht ->
                        val berichtKey = BerichtenCache.berichtKey(bericht.berichtId)
                        val fields = berichtToHash(bericht)
                        txHash.hset(berichtKey, fields)
                            .chain { _ -> txKey.expire(berichtKey, TTL) }
                            .replaceWithVoid()
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

    override fun search(ontvanger: String?, q: String, page: Int, pageSize: Int): Uni<BerichtenPage> {
        val ontvangerFilter = ontvanger?.let { "@ontvanger:{${escapeTag(it)}}" } ?: ""
        val escapedQ = escapeRedisSearch(q)
        val query = "$ontvangerFilter (@onderwerp|afzender:$escapedQ)".trim()

        val offset = page * pageSize
        val queryArgs = QueryArgs()
            .limit(offset, pageSize)
            .sortByDescending("tijdstip")

        return redis.search().ftSearch(BerichtenCache.SEARCH_INDEX, query, queryArgs)
            .map { response ->
                val berichten = response.documents().map { doc -> documentToBericht(doc) }
                val total = response.count().toLong()
                val totalPages = if (total == 0L) 0 else ((total + pageSize - 1) / pageSize).toInt()
                BerichtenPage(berichten, page, pageSize, total, totalPages)
            }
            .onFailure().invoke { e -> log.errorf(e, "RediSearch query mislukt voor q=%s, ontvanger=%s", q, ontvanger) }
    }

    override fun getById(berichtId: UUID, ontvanger: String): Uni<Bericht?> {
        val berichtKey = BerichtenCache.berichtKey(berichtId)
        return redis.hash(String::class.java).hgetall(berichtKey)
            .map { fields ->
                if (fields.isEmpty()) return@map null
                val bericht = hashToBericht(fields)
                if (bericht.ontvanger != ontvanger) null else bericht
            }
            .onFailure().invoke { e -> log.errorf(e, "Redis getById mislukt voor berichtId=%s", berichtId) }
    }

    private fun berichtToHash(bericht: Bericht): Map<String, String> = mapOf(
        "berichtId" to bericht.berichtId.toString(),
        "afzender" to bericht.afzender,
        "ontvanger" to bericht.ontvanger,
        "onderwerp" to bericht.onderwerp,
        "tijdstip" to bericht.tijdstip.toString(),
        "magazijnId" to bericht.magazijnId,
    )

    private fun hashToBericht(fields: Map<String, String>): Bericht = Bericht(
        berichtId = UUID.fromString(fields["berichtId"]),
        afzender = fields["afzender"]!!,
        ontvanger = fields["ontvanger"]!!,
        onderwerp = fields["onderwerp"]!!,
        tijdstip = Instant.parse(fields["tijdstip"]),
        magazijnId = fields["magazijnId"]!!,
    )

    private fun documentToBericht(doc: io.quarkus.redis.datasource.search.Document): Bericht = Bericht(
        berichtId = UUID.fromString(doc.property("berichtId").asString()),
        afzender = doc.property("afzender").asString(),
        ontvanger = doc.property("ontvanger").asString(),
        onderwerp = doc.property("onderwerp").asString(),
        tijdstip = Instant.parse(doc.property("tijdstip").asString()),
        magazijnId = doc.property("magazijnId").asString(),
    )

    companion object {
        private val TTL = Duration.ofSeconds(60)
        private fun listKey(key: String) = "$key:list"
        private fun statusKey(key: String) = "$key:status"
        private fun lockKey(key: String) = "$key:lock"

        // Escape speciale tekens voor RediSearch full-text queries
        internal val SEARCH_SPECIAL = Regex("""[,.<>{}\[\]"':;!@#$%^&*()\-+=~\\/|? ]""")
        internal fun escapeRedisSearch(text: String): String =
            text.replace(SEARCH_SPECIAL) { "\\${it.value}" }

        // Escape speciale tekens voor RediSearch TAG filter-waarden
        internal val TAG_SPECIAL = Regex("""[,.<>{}\[\]"':;!@#$%^&*()\-+=~\\/|? ]""")
        internal fun escapeTag(value: String): String =
            value.replace(TAG_SPECIAL) { "\\${it.value}" }
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
    FOUT,
}
