package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.search.CreateArgs
import io.quarkus.redis.datasource.search.FieldType
import io.quarkus.redis.datasource.search.QueryArgs
import io.smallrye.mutiny.Uni
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface BerichtenCache {
    fun store(key: String, berichten: List<Bericht>): Uni<Void>
    fun storeAggregationStatus(key: String, status: AggregationStatus): Uni<Void>
    fun trySetAggregationStatus(key: String, status: AggregationStatus): Uni<Boolean>
    fun getAggregationStatus(key: String): Uni<AggregationStatus?>
    fun getPage(key: String, page: Int, pageSize: Int, afzender: String? = null, ontvanger: String? = null): Uni<BerichtenPage?>
    fun search(ontvanger: String, q: String, page: Int, pageSize: Int, afzender: String? = null): Uni<BerichtenPage>
    fun getById(berichtId: UUID, ontvanger: String): Uni<Bericht?>
    fun update(berichtId: UUID, ontvanger: String, status: String?, map: String?): Uni<Bericht?>
    fun addBericht(bericht: Bericht): Uni<Void>
    fun delete(berichtId: UUID, ontvanger: String): Uni<Void>

    companion object {
        fun cacheKey(ontvanger: String): String {
            val hash = sha256(ontvanger)
            return "berichtensessiecache:v1:$hash"
        }
        fun berichtKey(berichtId: UUID) = "bericht:v1:$berichtId"
        const val BERICHT_PREFIX = "bericht:v1:"
        const val SEARCH_INDEX = "berichten-idx"

        private fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }
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
        try {
            redis.search().ftDropIndex(BerichtenCache.SEARCH_INDEX)
                .await().atMost(Duration.ofSeconds(5))
            log.debugf("Bestaande RediSearch index '%s' verwijderd", BerichtenCache.SEARCH_INDEX)
        } catch (e: Exception) {
            if (isUnknownIndex(e)) {
                log.debugf("Geen bestaande RediSearch index '%s' om te verwijderen", BerichtenCache.SEARCH_INDEX)
            } else {
                // Redis onbereikbaar / permissie-issue / onverwachte fout → fail-fast bij startup
                throw IllegalStateException(
                    "Kan RediSearch index '${BerichtenCache.SEARCH_INDEX}' niet benaderen voor cleanup",
                    e,
                )
            }
        }
        try {
            val args = CreateArgs()
                .onHash()
                .prefixes(BerichtenCache.BERICHT_PREFIX)
                .indexedField("onderwerp", FieldType.TEXT)
                .indexedField("afzender", FieldType.TAG)
                .indexedField("ontvanger", FieldType.TAG)
                .indexedField("publicatietijdstip", FieldType.TAG)
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

    private fun isUnknownIndex(e: Throwable): Boolean {
        val msg = (e.message ?: "").lowercase()
        return "unknown index" in msg || "no such index" in msg
    }

    override fun store(key: String, berichten: List<Bericht>): Uni<Void> {
        val listKey = listKey(key)
        if (berichten.isEmpty()) {
            return redis.key().del(listKey).replaceWithVoid()
        }

        val sorted = berichten.sortedByDescending { it.publicatietijdstip }
        val jsonValues = sorted.map { objectMapper.writeValueAsString(it) }

        // Gebruik een Redis transaction (MULTI/EXEC) zodat alle commands in één round-trip gaan
        return redis.withTransaction { tx ->
            val txList = tx.list(String::class.java)
            val txKey = tx.key()
            val txHash = tx.hash(String::class.java)

            txKey.del(listKey)
                .chain { _ -> txList.rpush(listKey, *jsonValues.toTypedArray()) }
                .chain { _ -> txKey.expire(listKey, ttl) }
                .chain { _ ->
                    // Sla elk bericht op als Hash voor RediSearch full-text index en lookup by ID
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

    override fun trySetAggregationStatus(key: String, status: AggregationStatus): Uni<Boolean> {
        val lockKey = lockKey(key)
        val statusKey = statusKey(key)
        val json = objectMapper.writeValueAsString(status)
        return redis.value(String::class.java).setnx(lockKey, "1")
            .chain { wasSet ->
                if (wasSet) {
                    redis.key().expire(lockKey, ttl)
                        .chain { _ -> redis.value(String::class.java).setex(statusKey, ttl.seconds, json) }
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

    override fun getPage(key: String, page: Int, pageSize: Int, afzender: String?, ontvanger: String?): Uni<BerichtenPage?> {
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
            // Sliding TTL: elke succesvolle read verlengt de sessie-keys zodat actieve gebruikers
            // hun cache niet verliezen tijdens een sessie.
            .call { result -> if (result != null) renewSessionTtl(key) else Uni.createFrom().voidItem() }
            .onFailure().invoke { e -> log.errorf(e, "Redis getPage mislukt voor key=%s, page=%d", key, page) }
    }

    private fun getPageFiltered(page: Int, pageSize: Int, ontvanger: String, afzender: String): Uni<BerichtenPage?> {
        val ontvangerFilter = "@ontvanger:{${escapeTag(ontvanger)}}"
        val afzenderFilter = "@afzender:{${escapeTag(afzender)}}"
        val query = "$ontvangerFilter $afzenderFilter"

        val offset = page * pageSize
        val queryArgs = QueryArgs()
            .limit(offset, pageSize)
            .sortByDescending("publicatietijdstip")

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

    override fun search(ontvanger: String, q: String, page: Int, pageSize: Int, afzender: String?): Uni<BerichtenPage> {
        val ontvangerFilter = "@ontvanger:{${escapeTag(ontvanger)}}"
        val escapedQ = escapeRedisSearch(q)
        val afzenderFilter = if (afzender != null) " @afzender:{${escapeTag(afzender)}}" else ""
        val query = "$ontvangerFilter (@onderwerp:$escapedQ)$afzenderFilter".trim()

        val offset = page * pageSize
        val queryArgs = QueryArgs()
            .limit(offset, pageSize)
            .sortByDescending("publicatietijdstip")

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

    override fun getById(berichtId: UUID, ontvanger: String): Uni<Bericht?> {
        val berichtKey = BerichtenCache.berichtKey(berichtId)
        return redis.hash(String::class.java).hgetall(berichtKey)
            .map { fields ->
                if (fields.isEmpty()) return@map null
                val bericht = hashToBericht(fields)
                if (bericht.ontvanger != ontvanger) null else bericht
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
    private fun renewReadTtl(ontvanger: String, berichten: List<Bericht>?): Uni<Void> {
        val cacheKey = BerichtenCache.cacheKey(ontvanger)
        val sessionRenew = renewSessionTtl(cacheKey)
        if (berichten.isNullOrEmpty()) return sessionRenew
        val hashRenews = berichten.map { bericht ->
            redis.key().expire(BerichtenCache.berichtKey(bericht.berichtId), ttl)
        }
        return Uni.combine().all()
            .unis(sessionRenew, Uni.join().all(hashRenews).andFailFast())
            .discardItems()
            .onFailure().recoverWithNull().replaceWithVoid()
    }

    private fun berichtToHash(bericht: Bericht): Map<String, String> = buildMap {
        put("berichtId", bericht.berichtId.toString())
        put("afzender", bericht.afzender)
        put("ontvanger", bericht.ontvanger)
        put("onderwerp", bericht.onderwerp)
        put("inhoud", bericht.inhoud)
        put("publicatietijdstip", bericht.publicatietijdstip.toString())
        put("magazijnId", bericht.magazijnId)
        put("aantalBijlagen", bericht.aantalBijlagen.toString())
        // Bijlagen worden als één JSON-string opgeslagen — RediSearch heeft geen index op
        // dit veld, en de lijst is zelden meer dan een handvol items, dus de overhead is laag.
        if (bericht.bijlagen.isNotEmpty()) {
            put("bijlagen", objectMapper.writeValueAsString(bericht.bijlagen))
        }
        bericht.map?.let { put("map", it) }
        bericht.status?.let { put("status", it) }
    }

    private fun hashToBericht(fields: Map<String, String>): Bericht = Bericht(
        berichtId = UUID.fromString(fields["berichtId"]),
        afzender = fields["afzender"]!!,
        ontvanger = fields["ontvanger"]!!,
        onderwerp = fields["onderwerp"]!!,
        // Backwards-compat: oude hash-entries hebben nog geen `inhoud`/`bijlagen`/`map`.
        // Default leeg/null zodat een upgrade tijdens een lopende sessie niet faalt.
        inhoud = fields["inhoud"] ?: "",
        publicatietijdstip = Instant.parse(fields["publicatietijdstip"]!!),
        magazijnId = fields["magazijnId"]!!,
        aantalBijlagen = fields["aantalBijlagen"]?.toInt() ?: 0,
        bijlagen = fields["bijlagen"]?.let { objectMapper.readValue(it, BIJLAGE_LIST_TYPE) } ?: emptyList(),
        map = fields["map"],
        status = fields["status"],
    )

    private fun documentToBericht(doc: io.quarkus.redis.datasource.search.Document): Bericht = Bericht(
        berichtId = UUID.fromString(doc.property("berichtId").asString()),
        afzender = doc.property("afzender").asString(),
        ontvanger = doc.property("ontvanger").asString(),
        onderwerp = doc.property("onderwerp").asString(),
        inhoud = doc.property("inhoud")?.asString() ?: "",
        publicatietijdstip = Instant.parse(doc.property("publicatietijdstip").asString()),
        magazijnId = doc.property("magazijnId").asString(),
        aantalBijlagen = doc.property("aantalBijlagen")?.asString()?.toInt() ?: 0,
        bijlagen = doc.property("bijlagen")?.asString()?.let { objectMapper.readValue(it, BIJLAGE_LIST_TYPE) } ?: emptyList(),
        map = doc.property("map")?.asString(),
        status = doc.property("status")?.asString(),
    )

    override fun update(berichtId: UUID, ontvanger: String, status: String?, map: String?): Uni<Bericht?> {
        // Merge-PATCH: alleen de meegegeven velden worden bijgewerkt; ontbrekende velden
        // (null op deze laag) blijven onveranderd. HSET met meerdere field/value-paren in
        // één call beperkt round-trips; bij geen wijzigingen retourneren we direct.
        val berichtKey = BerichtenCache.berichtKey(berichtId)
        return redis.hash(String::class.java).hgetall(berichtKey)
            .chain { fields ->
                if (fields.isEmpty()) return@chain Uni.createFrom().nullItem<Bericht>()
                val bericht = hashToBericht(fields)
                if (bericht.ontvanger != ontvanger) return@chain Uni.createFrom().nullItem<Bericht>()

                val updated = bericht.copy(
                    status = status ?: bericht.status,
                    map = map ?: bericht.map,
                )
                val toWrite = buildMap {
                    status?.let { put("status", it) }
                    map?.let { put("map", it) }
                }
                if (toWrite.isEmpty()) {
                    Uni.createFrom().item(updated)
                } else {
                    redis.hash(String::class.java).hset(berichtKey, toWrite)
                        .replaceWith(updated)
                }
            }
            .onFailure().invoke { e -> log.errorf(e, "Redis update mislukt voor berichtId=%s", berichtId) }
    }

    override fun addBericht(bericht: Bericht): Uni<Void> {
        val cacheKey = BerichtenCache.cacheKey(bericht.ontvanger)
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

    // Plan dat de read-fase (onder WATCH) doorgeeft aan de write-fase van de transactie.
    private data class DeletePlan(val uitvoeren: Boolean, val gefilterdeLijst: List<String>)

    override fun delete(berichtId: UUID, ontvanger: String): Uni<Void> {
        // Idempotent cache-invalidate: ontbrekende of niet-bijbehorende entries leveren geen fout op.
        // De sessie-`list` bevat JSON-blobs (gevuld via `addBericht.rpush`) die `getPage` direct
        // deserialiseert — dus na DEL op de hash blijft het bericht zonder list-prune zichtbaar
        // in `GET /berichten`. We filteren de matchende `berichtId`-entries uit de list (op
        // `berichtId` uit de geparseerde JSON, niet op exacte stringequality) en schrijven de
        // gefilterde list terug.
        //
        // Read en rewrite draaien onder optimistic locking (WATCH op list- en bericht-key): een
        // concurrent `addBericht` die tussen de read en EXEC een entry toevoegt, breekt de
        // transactie af zodat we zijn nieuwe entry niet overschrijven. Bij afbreken herhalen we
        // tot MAX_DELETE_POGINGEN; daarna laten we de cache met rust (zelf-helend via TTL) i.p.v.
        // een lost-update te riskeren.
        return deletePoging(berichtId, ontvanger, 1)
    }

    private fun deletePoging(berichtId: UUID, ontvanger: String, poging: Int): Uni<Void> {
        val berichtKey = BerichtenCache.berichtKey(berichtId)
        val cacheKey = BerichtenCache.cacheKey(ontvanger)
        val listKey = listKey(cacheKey)

        return redis.withTransaction<DeletePlan>(
            { reads ->
                reads.hash(String::class.java).hgetall(berichtKey)
                    .chain { fields ->
                        if (fields.isEmpty() || fields["ontvanger"] != ontvanger) {
                            Uni.createFrom().item(DeletePlan(uitvoeren = false, gefilterdeLijst = emptyList()))
                        } else {
                            reads.list(String::class.java).lrange(listKey, 0, -1)
                                .map { entries ->
                                    val gefilterd = entries.filterNot { json ->
                                        runCatching { objectMapper.readTree(json).get("berichtId")?.asText() }
                                            .getOrNull() == berichtId.toString()
                                    }

                                    DeletePlan(uitvoeren = true, gefilterdeLijst = gefilterd)
                                }
                        }
                    }
            },
            { plan, tx ->
                if (!plan.uitvoeren) {
                    Uni.createFrom().voidItem()
                } else {
                    val txKey = tx.key()
                    val txList = tx.list(String::class.java)
                    val rebuildList = if (plan.gefilterdeLijst.isNotEmpty()) {
                        txKey.del(listKey)
                            .chain { _ -> txList.rpush(listKey, *plan.gefilterdeLijst.toTypedArray()) }
                            .chain { _ -> txKey.expire(listKey, ttl) }
                            .replaceWithVoid()
                    } else {
                        txKey.del(listKey).replaceWithVoid()
                    }

                    txKey.del(berichtKey)
                        .chain { _ -> rebuildList }
                        .replaceWithVoid()
                }
            },
            listKey,
            berichtKey,
        ).chain { result ->
            if (result.discarded() && poging < MAX_DELETE_POGINGEN) {
                deletePoging(berichtId, ontvanger, poging + 1)
            } else {
                if (result.discarded()) {
                    log.warnf(
                        "Redis delete na %d pogingen afgebroken door concurrente wijziging; cache zelf-helend via TTL. berichtId=%s",
                        poging,
                        berichtId,
                    )
                }

                Uni.createFrom().voidItem()
            }
        }
            .onFailure().invoke { e -> log.errorf(e, "Redis delete mislukt voor berichtId=%s", berichtId) }
    }

    companion object {
        // Aantal optimistic-lock-pogingen voor `delete` voordat de invalidate wordt
        // opgegeven; concurrente wijziging op één sessie-list is zeldzaam, dus een klein
        // plafond volstaat en voorkomt ongebonden retry onder pathologische contentie.
        private const val MAX_DELETE_POGINGEN = 5

        private fun listKey(key: String) = "$key:list"
        private fun statusKey(key: String) = "$key:status"
        private fun lockKey(key: String) = "$key:lock"

        // TypeReference voor Jackson-deserialisatie van de `bijlagen`-hash-field (JSON-array).
        private val BIJLAGE_LIST_TYPE = object : TypeReference<List<BijlageSamenvatting>>() {}

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
