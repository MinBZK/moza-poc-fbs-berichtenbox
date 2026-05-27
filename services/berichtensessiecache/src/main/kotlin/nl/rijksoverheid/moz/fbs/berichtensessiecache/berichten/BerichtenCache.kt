package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.search.CreateArgs
import io.quarkus.redis.datasource.search.FieldType
import io.quarkus.redis.datasource.search.QueryArgs
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.Uni
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

interface BerichtenCache {
    fun store(key: String, berichten: List<Bericht>): Uni<Void>
    fun storeAggregationStatus(key: String, status: AggregationStatus): Uni<Void>
    fun updateAggregationStatus(key: String, status: AggregationStatus): Uni<Void>
    fun trySetAggregationStatus(key: String, status: AggregationStatus): Uni<Boolean>
    fun getAggregationStatus(key: String): Uni<AggregationStatus?>
    fun getPage(key: String, page: Int, pageSize: Int, afzender: String? = null, ontvanger: Identificatienummer? = null): Uni<BerichtenPage?>
    fun search(ontvanger: Identificatienummer, q: String, page: Int, pageSize: Int, afzender: String? = null): Uni<BerichtenPage>
    fun getById(berichtId: UUID, ontvanger: Identificatienummer): Uni<Bericht?>
    fun updateStatus(berichtId: UUID, ontvanger: Identificatienummer, status: String): Uni<Bericht?>
    fun addBericht(bericht: Bericht, ontvanger: Identificatienummer): Uni<Void>

    companion object {
        // ThreadLocal MessageDigest + HexFormat: bespaart `getInstance("SHA-256")`-allocatie
        // én per-byte `String.format("%02x", ...)` per cacheKey-call. cacheKey wordt per
        // request meerdere keren aangeroepen (getBerichten, getById, addBericht, etc.).
        private val SHA256_DIGEST: ThreadLocal<MessageDigest> = ThreadLocal.withInitial {
            MessageDigest.getInstance("SHA-256")
        }
        private val HEX = HexFormat.of()

        fun cacheKey(ontvanger: Identificatienummer): String {
            val canonical = ontvanger.toCanonicalString()
            val digest = SHA256_DIGEST.get().apply { reset() }
                .digest(canonical.toByteArray(Charsets.UTF_8))
            return "berichtensessiecache:v1:${HEX.formatHex(digest)}"
        }
        fun berichtKey(berichtId: UUID) = "bericht:v1:$berichtId"
        const val BERICHT_PREFIX = "bericht:v1:"
        const val SEARCH_INDEX = "berichten-idx"
    }
}

@ApplicationScoped
class RedisBerichtenCache(
    private val redis: ReactiveRedisDataSource,
    private val objectMapper: ObjectMapper,
    @param:ConfigProperty(name = "berichtensessiecache.ttl", defaultValue = "PT60S")
    private val ttl: Duration,
) : BerichtenCache {
    private val log = Logger.getLogger(RedisBerichtenCache::class.java)

    @PostConstruct
    fun init() {
        // Idempotente bootstrap: pods van een rolling-restart delen één Redis-cluster.
        // Als een eerdere pod de index al heeft aangemaakt, NIET droppen — dat zou queries
        // van andere replicas tijdelijk laten falen ("Unknown index name") en hun bestaande
        // berichthashes uit de search-index halen tot de eerstvolgende store(). Schema-
        // wijzigingen zijn zeldzaam en worden handmatig uitgevoerd via de operations-
        // handleiding `docs/operations/redisearch-schema-bump.md` (FT.DROPINDEX +
        // FT.CREATE in een geplande maintenance-window).
        //
        // Presence-check via FT._LIST i.p.v. drop-en-catch op message-match. Vermijdt
        // fragility bij Redis-versie-bump (foutmelding-tekst is geen API-contract).
        val bestaandeIndexen = try {
            redis.search().ft_list().await().atMost(Duration.ofSeconds(5))
        } catch (e: Exception) {
            throw IllegalStateException(
                "Kan RediSearch indexen niet opvragen (Redis onbereikbaar bij startup?)",
                e,
            )
        }

        if (BerichtenCache.SEARCH_INDEX in bestaandeIndexen) {
            log.infof(
                "RediSearch index '%s' bestaat al — laat ongemoeid (idempotente bootstrap). " +
                    "Schema-wijzigingen vereisen handmatige operations-procedure.",
                BerichtenCache.SEARCH_INDEX,
            )
            return
        }

        try {
            val args = CreateArgs()
                .onHash()
                .prefixes(BerichtenCache.BERICHT_PREFIX)
                .indexedField("onderwerp", FieldType.TEXT)
                .indexedField("afzender", FieldType.TAG)
                .indexedField("ontvanger", FieldType.TAG)
                .indexedField("tijdstip", FieldType.TAG)
            redis.search().ftCreate(BerichtenCache.SEARCH_INDEX, args)
                .await().atMost(Duration.ofSeconds(5))
            log.infof("RediSearch index '%s' aangemaakt", BerichtenCache.SEARCH_INDEX)
        } catch (e: Exception) {
            // Zonder search-index werken filter- en zoek-endpoints niet; laat de container fail-fast
            // starten in plaats van stilzwijgend te degraderen.
            throw IllegalStateException(
                "RediSearch index '${BerichtenCache.SEARCH_INDEX}' kon niet worden aangemaakt",
                e,
            )
        }
    }

    override fun store(key: String, berichten: List<Bericht>): Uni<Void> {
        val listKey = listKey(key)
        if (berichten.isEmpty()) {
            return redis.key().del(listKey).replaceWithVoid()
        }

        val sorted = berichten.sortedByDescending { it.tijdstip }
        val jsonValues = sorted.map { objectMapper.writeValueAsString(it) }

        return redis.withTransaction { tx ->
            val txList = tx.list(String::class.java)
            val txKey = tx.key()
            val txHash = tx.hash(String::class.java)

            txKey.del(listKey)
                .chain { _ -> txList.rpush(listKey, *jsonValues.toTypedArray()) }
                .chain { _ -> txKey.expire(listKey, ttl) }
                .chain { _ ->
                    val stores = sorted.map { bericht ->
                        val berichtKey = BerichtenCache.berichtKey(bericht.berichtId)
                        val fields = berichtToHash(bericht)
                        txHash.hset(berichtKey, fields)
                            .chain { _ -> txKey.expire(berichtKey, ttl) }
                            .replaceWithVoid()
                    }
                    Uni.join().all(stores).andFailFast().replaceWithVoid()
                }
        }.replaceWithVoid()
            .invoke { _ -> log.debugf("Opgeslagen %d berichten in cache", berichten.size) }
            .onFailure().invoke { e -> log.errorf(e, "Redis store mislukt voor key=%s", key) }
    }

    override fun storeAggregationStatus(key: String, status: AggregationStatus): Uni<Void> {
        val statusKey = statusKey(key)
        val json = objectMapper.writeValueAsString(status)
        return redis.value(String::class.java).setex(statusKey, ttl.seconds, json)
            .chain { _ -> redis.key().del(lockKey(key)) }
            .replaceWithVoid()
            .onFailure().invoke { e -> log.errorf(e, "Redis storeAggregationStatus mislukt voor key=%s", key) }
    }

    override fun updateAggregationStatus(key: String, status: AggregationStatus): Uni<Void> {
        val statusKey = statusKey(key)
        val json = objectMapper.writeValueAsString(status)
        return redis.value(String::class.java).setex(statusKey, ttl.seconds, json)
            .replaceWithVoid()
            .onFailure().invoke { e -> log.errorf(e, "Redis updateAggregationStatus mislukt voor key=%s", key) }
    }

    override fun trySetAggregationStatus(key: String, status: AggregationStatus): Uni<Boolean> {
        val lockKey = lockKey(key)
        val statusKey = statusKey(key)

        // SET NX EX in één commando: atomair — geen tussenliggend venster waarbij de lock
        // bestaat zonder TTL (wat bij losse SETNX + EXPIRE zou kunnen optreden als EXPIRE faalt).
        val lockArgs = SetArgs().nx().ex(ttl.seconds)

        return redis.value(String::class.java).setAndChanged(lockKey, "1", lockArgs)
            .chain { wasSet ->
                if (wasSet) {
                    // Lazy JSON-serialisatie: alleen serializen als lock daadwerkelijk genomen is.
                    // Bij hoge concurrent-409-druk bespaart dit Jackson-werk voor afgewezen requests.
                    val json = objectMapper.writeValueAsString(status)
                    // statusKey-fout na geslaagde lock-set: rollback de lock via best-effort DEL,
                    // anders blijft de ontvanger 60s op TTL hangen voor wat een transient Redis-blip was.
                    redis.value(String::class.java).setex(statusKey, ttl.seconds, json)
                        .onFailure().call { _ ->
                            redis.key().del(lockKey)
                                .onFailure().invoke { delErr ->
                                    log.errorf(delErr, "Lock-rollback na statusKey-fout faalde voor key=%s; lock leunt op TTL", key)
                                }
                                .onFailure().recoverWithNull()
                        }
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

    override fun getPage(key: String, page: Int, pageSize: Int, afzender: String?, ontvanger: Identificatienummer?): Uni<BerichtenPage?> {
        if (afzender != null && ontvanger != null) {
            return getPageFiltered(page, pageSize, ontvanger, afzender)
        }

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
                    val berichten = try {
                        jsonList.map { objectMapper.readValue(it, Bericht::class.java) }
                    } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
                        // Cache-data niet deserialiseerbaar = schema-drift of corruptie. Aparte
                        // log + rethrow zodat dit niet als "Redis onbereikbaar" wegfiltert in het
                        // generieke onFailure-pad; caller (BerichtensessiecacheResource) heeft
                        // een eigen 500-pad voor JsonProcessingException.
                        log.errorf(ex, "Cache-bericht niet deserialiseerbaar voor key=%s (corruptie of schema-drift)", key)
                        throw ex
                    }
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
            // Sliding TTL: elke succesvolle read verlengt de sessie-keys zodat actieve gebruikers
            // hun cache niet verliezen tijdens een sessie.
            .call { result -> if (result != null) renewSessionTtl(key) else Uni.createFrom().voidItem() }
            .onFailure(com.fasterxml.jackson.core.JsonProcessingException::class.java)
                .invoke { _ -> /* al gelogd hierboven, geen dubbele log */ }
            .onFailure { ex -> ex !is com.fasterxml.jackson.core.JsonProcessingException }
                .invoke { e -> log.errorf(e, "Redis getPage mislukt voor key=%s, page=%d", key, page) }
    }

    private fun getPageFiltered(page: Int, pageSize: Int, ontvanger: Identificatienummer, afzender: String): Uni<BerichtenPage?> {
        val ontvangerFilter = "@ontvanger:{${escapeTag(ontvanger.waarde)}}"
        val afzenderFilter = "@afzender:{${escapeTag(afzender)}}"
        val query = "$ontvangerFilter $afzenderFilter"

        val offset = page * pageSize
        val queryArgs = QueryArgs()
            .limit(offset, pageSize)
            .sortByDescending("tijdstip")

        return redis.search().ftSearch(BerichtenCache.SEARCH_INDEX, query, queryArgs)
            .map { response ->
                val berichten = response.documents().map { doc -> documentToBericht(doc) }
                val total = response.count().toLong()
                if (total == 0L && berichten.isEmpty()) {
                    null
                } else {
                    val totalPages = if (total == 0L) 0 else ((total + pageSize - 1) / pageSize).toInt()
                    BerichtenPage(berichten, page, pageSize, total, totalPages)
                }
            }
            .call { result -> renewReadTtl(ontvanger, result?.berichten) }
            .onFailure().invoke { e -> log.errorf(e, "RediSearch getPageFiltered mislukt voor afzender=%s", afzender) }
    }

    override fun search(ontvanger: Identificatienummer, q: String, page: Int, pageSize: Int, afzender: String?): Uni<BerichtenPage> {
        val ontvangerFilter = "@ontvanger:{${escapeTag(ontvanger.waarde)}}"
        val escapedQ = escapeRedisSearch(q)
        val afzenderFilter = if (afzender != null) " @afzender:{${escapeTag(afzender)}}" else ""
        val query = "$ontvangerFilter (@onderwerp:$escapedQ)$afzenderFilter".trim()

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
            .call { result -> renewReadTtl(ontvanger, result.berichten) }
            .onFailure().invoke { e -> log.errorf(e, "RediSearch query mislukt voor q=%s", q) }
    }

    override fun getById(berichtId: UUID, ontvanger: Identificatienummer): Uni<Bericht?> {
        val berichtKey = BerichtenCache.berichtKey(berichtId)
        return redis.hash(String::class.java).hgetall(berichtKey)
            .map { fields ->
                if (fields.isEmpty()) return@map null
                val bericht = hashToBericht(fields)
                if (bericht.ontvanger != ontvanger.waarde) null else bericht
            }
            .call { bericht ->
                if (bericht != null) renewReadTtl(ontvanger, listOf(bericht))
                else Uni.createFrom().voidItem()
            }
            .onFailure().invoke { e -> log.errorf(e, "Redis getById mislukt voor berichtId=%s", berichtId) }
    }

    /**
     * Verlengt TTL op sessie-keys (list + status) zodat actieve gebruikers hun cache niet
     * verliezen. Faalt niet hard: als verlengen mislukt, wordt dat gelogd maar niet gepropageerd —
     * de oorspronkelijke read heeft al gelukt en de cache valt anders uiterlijk na `ttl` weg.
     */
    private fun renewSessionTtl(cacheKey: String): Uni<Void> {
        val listKey = listKey(cacheKey)
        val statusKey = statusKey(cacheKey)
        return Uni.combine().all()
            .unis(
                redis.key().expire(listKey, ttl),
                redis.key().expire(statusKey, ttl),
            ).discardItems()
            .onFailure().invoke { e -> log.warnf(e, "Sliding TTL verlengen mislukt voor cacheKey=%s", cacheKey) }
            .onFailure().recoverWithNull().replaceWithVoid()
    }

    /**
     * Verlengt TTL op sessie-keys én op de per-bericht hash keys die via een read zijn geraakt.
     * Zo blijven zowel de lijst als de berichten-hashes in sync qua TTL.
     */
    private fun renewReadTtl(ontvanger: Identificatienummer, berichten: List<Bericht>?): Uni<Void> {
        val cacheKey = BerichtenCache.cacheKey(ontvanger)
        if (berichten.isNullOrEmpty()) return renewSessionTtl(cacheKey)

        // MULTI/EXEC pipeline-batch: alle EXPIRE-commands (2 sessie-keys + N bericht-hashes)
        // in één round-trip i.p.v. losse calls. Op pageSize=100 scheelt dit 100+ RTT's.
        val listKey = listKey(cacheKey)
        val statusKey = statusKey(cacheKey)
        return redis.withTransaction { tx ->
            val txKey = tx.key()
            val expires = mutableListOf<Uni<Void>>()
            expires.add(txKey.expire(listKey, ttl))
            expires.add(txKey.expire(statusKey, ttl))
            berichten.forEach { bericht ->
                expires.add(txKey.expire(BerichtenCache.berichtKey(bericht.berichtId), ttl))
            }
            Uni.join().all(expires).andFailFast().replaceWithVoid()
        }.replaceWithVoid()
            // Log + slik: TTL-renew is best-effort. Bij stille discard zou een Redis-storing
            // in de batch ongezien blijven; de read zelf is al gelukt.
            .onFailure().invoke { e -> log.warnf(e, "Sliding TTL renewReadTtl mislukt voor cacheKey=%s (read geslaagd, TTL niet verlengd)", cacheKey) }
            .onFailure().recoverWithNull().replaceWithVoid()
    }

    private fun berichtToHash(bericht: Bericht): Map<String, String> = buildMap {
        put("berichtId", bericht.berichtId.toString())
        put("afzender", bericht.afzender)
        put("ontvanger", bericht.ontvanger)
        put("onderwerp", bericht.onderwerp)
        put("tijdstip", bericht.tijdstip.toString())
        put("magazijnId", bericht.magazijnId)
        bericht.status?.let { put("status", it) }
    }

    private fun hashToBericht(fields: Map<String, String>): Bericht {
        fun required(name: String): String = fields[name]
            ?: throw CacheCorruptedException.veldOntbreekt(name)

        val berichtIdStr = required("berichtId")
        val tijdstipStr = required("tijdstip")

        return Bericht(
            berichtId = try {
                UUID.fromString(berichtIdStr)
            } catch (ex: IllegalArgumentException) {
                throw CacheCorruptedException.onleesbareWaarde("berichtId", ex)
            },
            afzender = required("afzender"),
            ontvanger = required("ontvanger"),
            onderwerp = required("onderwerp"),
            tijdstip = try {
                Instant.parse(tijdstipStr)
            } catch (ex: java.time.format.DateTimeParseException) {
                throw CacheCorruptedException.onleesbareWaarde("tijdstip", ex)
            },
            magazijnId = required("magazijnId"),
            status = fields["status"],
        )
    }

    private fun documentToBericht(doc: io.quarkus.redis.datasource.search.Document): Bericht {
        // Wrap parse-fouten in CacheCorruptedException zodat zoek-/filter-pad een
        // corrupte cache-entry niet als generieke "RediSearch query mislukt" rapporteert
        // (verkeerde diagnose). Consistent met hashToBericht-pad voor getById.
        val berichtIdStr = doc.property("berichtId").asString()
        val tijdstipStr = doc.property("tijdstip").asString()

        return Bericht(
            berichtId = try {
                UUID.fromString(berichtIdStr)
            } catch (ex: IllegalArgumentException) {
                throw CacheCorruptedException.onleesbareWaarde("berichtId", ex)
            },
            afzender = doc.property("afzender").asString(),
            ontvanger = doc.property("ontvanger").asString(),
            onderwerp = doc.property("onderwerp").asString(),
            tijdstip = try {
                Instant.parse(tijdstipStr)
            } catch (ex: java.time.format.DateTimeParseException) {
                throw CacheCorruptedException.onleesbareWaarde("tijdstip", ex)
            },
            magazijnId = doc.property("magazijnId").asString(),
            status = doc.property("status")?.asString(),
        )
    }

    override fun updateStatus(berichtId: UUID, ontvanger: Identificatienummer, status: String): Uni<Bericht?> {
        val berichtKey = BerichtenCache.berichtKey(berichtId)
        return redis.hash(String::class.java).hgetall(berichtKey)
            .chain { fields ->
                if (fields.isEmpty()) return@chain Uni.createFrom().nullItem<Bericht>()
                val bericht = hashToBericht(fields)
                if (bericht.ontvanger != ontvanger.waarde) return@chain Uni.createFrom().nullItem<Bericht>()
                val updated = bericht.copy(status = status)
                redis.hash(String::class.java).hset(berichtKey, "status", status)
                    .replaceWith(updated)
            }
            .onFailure().invoke { e -> log.errorf(e, "Redis updateStatus mislukt voor berichtId=%s", berichtId) }
    }

    override fun addBericht(bericht: Bericht, ontvanger: Identificatienummer): Uni<Void> {
        val cacheKey = BerichtenCache.cacheKey(ontvanger)
        val listKey = listKey(cacheKey)
        val berichtKey = BerichtenCache.berichtKey(bericht.berichtId)
        val json = objectMapper.writeValueAsString(bericht)
        val fields = berichtToHash(bericht)

        return redis.withTransaction { tx ->
            val txList = tx.list(String::class.java)
            val txHash = tx.hash(String::class.java)
            val txKey = tx.key()

            txList.rpush(listKey, json)
                .chain { _ -> txKey.expire(listKey, ttl) }
                .chain { _ -> txHash.hset(berichtKey, fields) }
                .chain { _ -> txKey.expire(berichtKey, ttl) }
                .replaceWithVoid()
        }.replaceWithVoid()
            .invoke { _ -> log.debugf("Bericht %s toegevoegd aan cache", bericht.berichtId) }
            .onFailure().invoke { e -> log.errorf(e, "Redis addBericht mislukt voor berichtId=%s", bericht.berichtId) }
    }

    companion object {
        private fun listKey(key: String) = "$key:list"
        private fun statusKey(key: String) = "$key:status"
        private fun lockKey(key: String) = "$key:lock"

        // Escape niet-alfanumerieke tekens voor RediSearch full-text queries (whitelist-benadering)
        internal val SEARCH_SPECIAL = Regex("[^a-zA-Z0-9]")
        internal fun escapeRedisSearch(text: String): String =
            text.replace(SEARCH_SPECIAL) { "\\${it.value}" }

        // Escape niet-alfanumerieke tekens voor RediSearch TAG filter-waarden (whitelist-benadering)
        internal val TAG_SPECIAL = Regex("[^a-zA-Z0-9]")
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
