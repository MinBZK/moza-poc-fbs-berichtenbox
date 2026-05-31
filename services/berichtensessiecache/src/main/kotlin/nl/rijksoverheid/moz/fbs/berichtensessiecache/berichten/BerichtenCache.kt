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

/**
 * Transiente schrijf-contentie: een optimistic-lock-update is na alle pogingen
 * afgebroken door gelijktijdige wijzigingen. Het bericht bestáát — alleen de
 * cache-write lukte niet. Onderscheidt zich expliciet van "niet gevonden" zodat
 * de resource een retriable 503 geeft i.p.v. een misleidende 404 (na een
 * geslaagde magazijn-write zou een 404 de client laten denken dat het bericht weg is).
 */
class CacheContentieException(berichtId: UUID) :
    RuntimeException("Cache-update afgebroken door gelijktijdige wijziging voor berichtId=$berichtId")

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
        val queryArgs = samenvattingQueryArgs()
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
        val queryArgs = samenvattingQueryArgs()
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

    // Beperk de FT.SEARCH-projectie tot de samenvatting-velden: de lijst-/zoek-respons heeft
    // `inhoud`/`bijlagen` niet nodig (die worden door de samenvatting-mapper weggegooid), dus
    // het is verspilling om die — potentieel grote — velden over de wire op te halen.
    // `documentToBericht` vult de afwezige velden veilig met de backwards-compat-defaults
    // ("" / lege lijst). De detail-lookup (`getById`) gebruikt de hash en blijft volledig.
    private fun samenvattingQueryArgs(): QueryArgs {
        val args = QueryArgs()
        SAMENVATTING_VELDEN.forEach { args.returnAttribute(it) }
        return args
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
        // Bewaar de wire-string ("gelezen"/"ongelezen") zodat het hash-veld en de
        // RediSearch-index-waarden ongewijzigd blijven t.o.v. de stringly-typed versie.
        bericht.status?.let { put("status", it.wire) }
    }

    private fun hashToBericht(fields: Map<String, String>): Bericht {
        val berichtId = fields["berichtId"]
        return Bericht(
            // Kernvelden zijn altijd geschreven door `berichtToHash`; ontbreken duidt op een
            // corrupte/partiële hash-entry. Faal met een veld-specifieke melding i.p.v. een
            // kale NPE, die als misleidende "Cache niet bereikbaar" 503 zou opduiken.
            berichtId = UUID.fromString(berichtId),
            afzender = fields["afzender"] ?: error("cache-hash mist 'afzender' (corrupt/partial entry) berichtId=$berichtId"),
            ontvanger = fields["ontvanger"] ?: error("cache-hash mist 'ontvanger' (corrupt/partial entry) berichtId=$berichtId"),
            onderwerp = fields["onderwerp"] ?: error("cache-hash mist 'onderwerp' (corrupt/partial entry) berichtId=$berichtId"),
            // Backwards-compat: oude hash-entries hebben nog geen `inhoud`/`bijlagen`/`map`.
            // Default leeg/null zodat een upgrade tijdens een lopende sessie niet faalt.
            inhoud = fields["inhoud"] ?: "",
            publicatietijdstip = Instant.parse(
                fields["publicatietijdstip"] ?: error("cache-hash mist 'publicatietijdstip' (corrupt/partial entry) berichtId=$berichtId"),
            ),
            magazijnId = fields["magazijnId"] ?: error("cache-hash mist 'magazijnId' (corrupt/partial entry) berichtId=$berichtId"),
            aantalBijlagen = fields["aantalBijlagen"]?.toInt() ?: 0,
            bijlagen = fields["bijlagen"]?.let { objectMapper.readValue(it, BIJLAGE_LIST_TYPE) } ?: emptyList(),
            map = fields["map"],
            status = fields["status"]?.let { Leesstatus.fromWire(it) },
        )
    }

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
        status = doc.property("status")?.asString()?.let { Leesstatus.fromWire(it) },
    )

    // Plan dat de read-fase (onder WATCH) doorgeeft aan de write-fase van de update-transactie.
    private sealed interface UpdatePlan {
        // Hash ontbreekt of hoort bij een andere ontvanger → resultaat null (geen mutatie).
        data object Geen : UpdatePlan

        // Niets te schrijven (status==null && map==null): retourneer het ongewijzigde bericht
        // zonder write, zodat we geen onnodige TTL-renew of list-rewrite forceren.
        data class Ongewijzigd(val bericht: Bericht) : UpdatePlan

        // Te schrijven hash-velden + de herbouwde list met het vervangen blob op `berichtId`.
        data class Wijzig(
            val updated: Bericht,
            val hashVelden: Map<String, String>,
            val nieuweLijst: List<String>,
        ) : UpdatePlan
    }

    override fun update(berichtId: UUID, ontvanger: String, status: String?, map: String?): Uni<Bericht?> {
        // Merge-PATCH: alleen de meegegeven velden worden bijgewerkt; ontbrekende velden blijven
        // onveranderd. De sessie-`list` bevat volledige JSON-blobs die het ongefilterde `getPage`
        // direct deserialiseert — een HSET-alleen update laat die list stale (oude status/map
        // zichtbaar in `GET /berichten` tot TTL). We schrijven daarom óók de matchende list-entry
        // terug, onder hetzelfde optimistic locking als `delete` (WATCH op list- en bericht-key):
        // een concurrente `addBericht`/`delete` tussen read en EXEC breekt de transactie af zodat
        // we geen lost-update veroorzaken. Bij afbreken herhalen we tot een klein plafond; daarna
        // laten we de cache met rust (zelf-helend via TTL).
        val leesstatus = status?.let { Leesstatus.fromWire(it) }
        return updatePoging(berichtId, ontvanger, leesstatus, map, 1)
    }

    private fun updatePoging(
        berichtId: UUID,
        ontvanger: String,
        status: Leesstatus?,
        map: String?,
        poging: Int,
    ): Uni<Bericht?> {
        val berichtKey = BerichtenCache.berichtKey(berichtId)
        val cacheKey = BerichtenCache.cacheKey(ontvanger)
        val listKey = listKey(cacheKey)

        return redis.withTransaction<UpdatePlan>(
            { reads ->
                reads.hash(String::class.java).hgetall(berichtKey)
                    .chain { fields ->
                        if (fields.isEmpty()) {
                            Uni.createFrom().item(UpdatePlan.Geen)
                        } else {
                            val bericht = hashToBericht(fields)

                            if (bericht.ontvanger != ontvanger) {
                                Uni.createFrom().item(UpdatePlan.Geen)
                            } else if (status == null && map == null) {
                                Uni.createFrom().item(UpdatePlan.Ongewijzigd(bericht))
                            } else {
                                val updated = bericht.copy(
                                    status = status ?: bericht.status,
                                    map = map ?: bericht.map,
                                )
                                val hashVelden = buildMap {
                                    status?.let { put("status", it.wire) }
                                    map?.let { put("map", it) }
                                }
                                val updatedJson = objectMapper.writeValueAsString(updated)

                                reads.list(String::class.java).lrange(listKey, 0, -1)
                                    .map { entries ->
                                        val nieuweLijst = entries.map { json ->
                                            val match = runCatching { objectMapper.readTree(json).get("berichtId")?.asText() }
                                                .onFailure { e ->
                                                    // Onparsebare blob: kan per definitie niet het te
                                                    // updaten bericht zijn; behoud en log (cache-corruptie).
                                                    log.warnf(e, "Onparsebare list-entry bij update; entry behouden. berichtId=%s", berichtId)
                                                }
                                                .getOrNull() == berichtId.toString()

                                            if (match) updatedJson else json
                                        }

                                        UpdatePlan.Wijzig(updated, hashVelden, nieuweLijst)
                                    }
                            }
                        }
                    }
            },
            { plan, tx ->
                when (plan) {
                    UpdatePlan.Geen -> Uni.createFrom().voidItem()

                    is UpdatePlan.Ongewijzigd -> Uni.createFrom().voidItem()

                    is UpdatePlan.Wijzig -> {
                        val txKey = tx.key()
                        val txList = tx.list(String::class.java)
                        val txHash = tx.hash(String::class.java)

                        val rebuildList = if (plan.nieuweLijst.isNotEmpty()) {
                            txKey.del(listKey)
                                .chain { _ -> txList.rpush(listKey, *plan.nieuweLijst.toTypedArray()) }
                                .chain { _ -> txKey.expire(listKey, ttl) }
                                .replaceWithVoid()
                        } else {
                            Uni.createFrom().voidItem()
                        }

                        txHash.hset(berichtKey, plan.hashVelden)
                            .chain { _ -> txKey.expire(berichtKey, ttl) }
                            .chain { _ -> rebuildList }
                            .replaceWithVoid()
                    }
                }
            },
            listKey,
            berichtKey,
        ).chain { result ->
            val plan = result.preTransactionResult

            if (result.discarded() && poging < MAX_UPDATE_POGINGEN) {
                updatePoging(berichtId, ontvanger, status, map, poging + 1)
            } else if (result.discarded()) {
                // Contentie is géén not-found: een null zou de resource laten 404'en
                // terwijl het bericht bestaat (en de magazijn-write al geslaagd kan
                // zijn). Signaleer een retriable fout → 503 zodat de client opnieuw
                // kan proberen. Op warn: verwacht onder hoge gelijktijdigheid, geen crash.
                log.warnf(
                    "Redis update na %d pogingen afgebroken door concurrente wijziging; retriable 503. berichtId=%s",
                    poging,
                    berichtId,
                )

                Uni.createFrom().failure(CacheContentieException(berichtId))
            } else {
                when (plan) {
                    UpdatePlan.Geen -> Uni.createFrom().nullItem<Bericht>()
                    is UpdatePlan.Ongewijzigd -> Uni.createFrom().item(plan.bericht)
                    is UpdatePlan.Wijzig -> Uni.createFrom().item(plan.updated)
                }
            }
        }
            .onFailure().invoke { e ->
                // Contentie is al op warn gelogd en is geen echte storing; alleen
                // overige fouten als error loggen.
                if (e !is CacheContentieException) log.errorf(e, "Redis update mislukt voor berichtId=%s", berichtId)
            }
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
    // Sealed zodat de "niets te doen"-tak geen betekenisloze lege lijst hoeft te dragen
    // en de write-fase exhaustief op de twee toestanden matcht.
    private sealed interface DeletePlan {
        data object NietsTeDoen : DeletePlan

        data class Verwijder(val gefilterdeLijst: List<String>) : DeletePlan
    }

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
                            Uni.createFrom().item(DeletePlan.NietsTeDoen)
                        } else {
                            reads.list(String::class.java).lrange(listKey, 0, -1)
                                .map { entries ->
                                    val gefilterd = entries.filterNot { json ->
                                        runCatching { objectMapper.readTree(json).get("berichtId")?.asText() }
                                            .onFailure { e ->
                                                // Corrupte/legacy list-entry: conservatief behouden (kan
                                                // per definitie niet het te verwijderen bericht zijn), maar
                                                // wel loggen — een onparsebare blob duidt op cache-corruptie.
                                                log.warnf(e, "Onparsebare list-entry bij delete-prune; entry behouden. berichtId=%s", berichtId)
                                            }
                                            .getOrNull() == berichtId.toString()
                                    }

                                    DeletePlan.Verwijder(gefilterd)
                                }
                        }
                    }
            },
            { plan, tx ->
                when (plan) {
                    DeletePlan.NietsTeDoen -> Uni.createFrom().voidItem()

                    is DeletePlan.Verwijder -> {
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
                }
            },
            listKey,
            berichtKey,
        ).chain { result ->
            if (result.discarded() && poging < MAX_DELETE_POGINGEN) {
                deletePoging(berichtId, ontvanger, poging + 1)
            } else {
                if (result.discarded()) {
                    // errorf (niet warnf): de invalidate is niet toegepast, dus de
                    // stale entry overleeft tot TTL (≤60s). Self-healing, maar onder
                    // de uitvraag dual-write-compensatie betekent dit een tijdelijk
                    // stale cache ná een geslaagde magazijn-mutatie — die call krijgt
                    // hier een 2xx (idempotente delete), dus dit log is het enige
                    // alertbare signaal van de mislukte invalidate.
                    log.errorf(
                        "Redis delete na %d pogingen afgebroken door concurrente wijziging; cache stale tot TTL. berichtId=%s",
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

        // Idem voor `update`: zelfde optimistic-lock-retry-plafond als delete.
        private const val MAX_UPDATE_POGINGEN = 5

        private fun listKey(key: String) = "$key:list"
        private fun statusKey(key: String) = "$key:status"
        private fun lockKey(key: String) = "$key:lock"

        // TypeReference voor Jackson-deserialisatie van de `bijlagen`-hash-field (JSON-array).
        private val BIJLAGE_LIST_TYPE = object : TypeReference<List<BijlageSamenvatting>>() {}

        // Hash-velden die de samenvatting-mapper nodig heeft; gebruikt als FT.SEARCH RETURN-lijst
        // zodat list/zoek de zware `inhoud`/`bijlagen`-velden niet ophaalt.
        internal val SAMENVATTING_VELDEN = listOf(
            "berichtId",
            "afzender",
            "ontvanger",
            "onderwerp",
            "publicatietijdstip",
            "magazijnId",
            "aantalBijlagen",
            "map",
            "status",
        )

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
