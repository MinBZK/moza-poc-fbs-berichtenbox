package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.core.type.TypeReference
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
    fun updateAggregationStatus(key: String, status: AggregationStatus): Uni<Void>
    fun trySetAggregationStatus(key: String, status: AggregationStatus): Uni<Boolean>
    fun getAggregationStatus(key: String): Uni<AggregationStatus?>
    fun getPage(key: String, page: Int, pageSize: Int, afzender: String? = null, ontvanger: Identificatienummer? = null, map: String? = null): Uni<BerichtenPage?>
    fun search(ontvanger: Identificatienummer, q: String, page: Int, pageSize: Int, afzender: String? = null, map: String? = null): Uni<BerichtenPage>
    fun getById(berichtId: UUID, ontvanger: Identificatienummer): Uni<Bericht?>
    fun updateBerichtMetadata(berichtId: UUID, ontvanger: Identificatienummer, status: String?, map: String?): Uni<Bericht?>
    fun createBericht(bericht: Bericht, ontvanger: Identificatienummer): Uni<Void>
    fun delete(berichtId: UUID, ontvanger: Identificatienummer): Uni<Void>

    companion object {
        // ThreadLocal MessageDigest + HexFormat: bespaart `getInstance("SHA-256")`-allocatie
        // én per-byte `String.format("%02x", ...)` per cacheKey-call. cacheKey wordt per
        // request meerdere keren aangeroepen (getBerichten, getById, createBericht, etc.).
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
    @param:ConfigProperty(name = "berichtensessiecache.ttl", defaultValue = "PT12H")
    private val ttl: Duration,
    // Korte, aparte vangnet-TTL voor de aggregatie-lock én de BEZIG-status, losgekoppeld van de
    // cache-bewaartermijn (`ttl`). Crasht een pod midden in aggregatie, dan mag de ontvanger niet
    // langer dan deze timeout geblokkeerd blijven voor opnieuw `_ophalen` (met `ttl` was dat tot 12u).
    // Moet ruim boven de normale aggregatieduur (resolver outer-await + cache-await) liggen, anders
    // ontgrendelt een nog lopende aggregatie voortijdig en kan een concurrent `_ophalen` dubbel draaien.
    @param:ConfigProperty(name = "berichtensessiecache.aggregation-lock-ttl", defaultValue = "PT2M")
    private val aggregationLockTtl: Duration,
) : BerichtenCache {
    private val log = Logger.getLogger(RedisBerichtenCache::class.java)

    @PostConstruct
    fun init() {
        // Idempotente bootstrap: meerdere pods delen één Redis-cluster. Bestaande index
        // NIET droppen — dat laat queries van andere replicas tijdelijk falen. Schema-
        // wijzigingen lopen via de handmatige operations-procedure: docs/operations/redisearch-schema-bump.md.
        // Presence-check via FT._LIST i.p.v. drop-en-catch (foutmelding-tekst is geen API-contract).
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
                .indexedField("publicatietijdstip", FieldType.TAG)
                .indexedField("map", FieldType.TAG)
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

        val sorted = berichten.sortedByDescending { it.publicatietijdstip }
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
        // Nog-lopende (BEZIG) status: korte vangnet-TTL gelijk aan de lock, zodat een crash de
        // status niet 12u laat hangen. De terminale GEREED/FOUT-status (storeAggregationStatus)
        // volgt wél de lange cache-`ttl` omdat die het gecachte resultaat spiegelt.
        return redis.value(String::class.java).setex(statusKey, aggregationLockTtl.seconds, json)
            .replaceWithVoid()
            .onFailure().invoke { e -> log.errorf(e, "Redis updateAggregationStatus mislukt voor key=%s", key) }
    }

    override fun trySetAggregationStatus(key: String, status: AggregationStatus): Uni<Boolean> {
        val lockKey = lockKey(key)
        val statusKey = statusKey(key)

        // SET NX EX in één commando: atomair — geen tussenliggend venster waarbij de lock
        // bestaat zonder TTL (wat bij losse SETNX + EXPIRE zou kunnen optreden als EXPIRE faalt).
        val lockArgs = SetArgs().nx().ex(aggregationLockTtl.seconds)

        return redis.value(String::class.java).setAndChanged(lockKey, "1", lockArgs)
            .chain { wasSet ->
                if (wasSet) {
                    // Lazy JSON-serialisatie: alleen serializen als lock daadwerkelijk genomen is.
                    // Bij hoge concurrent-409-druk bespaart dit Jackson-werk voor afgewezen requests.
                    val json = objectMapper.writeValueAsString(status)
                    // statusKey-fout na geslaagde lock-set: rollback de lock via best-effort DEL,
                    // anders blijft de ontvanger op de aggregatie-lock-TTL hangen voor wat een transient
                    // Redis-blip was. De BEZIG-status deelt diezelfde korte vangnet-TTL als de lock.
                    redis.value(String::class.java).setex(statusKey, aggregationLockTtl.seconds, json)
                        .onFailure().call { _ ->
                            redis.key().del(lockKey)
                                .onFailure().invoke { delErr ->
                                    // ALERT-marker consistent met service-cleanup voor uniforme alert-routing.
                                    log.fatalf(delErr, "[ALERT cache_doublefail] Lock-rollback na statusKey-fout faalde voor key=%s; lock leunt op TTL", key)
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

    override fun getPage(key: String, page: Int, pageSize: Int, afzender: String?, ontvanger: Identificatienummer?, map: String?): Uni<BerichtenPage?> {
        if ((afzender != null || map != null) && ontvanger != null) {
            // `key` is al de cacheKey (caller berekende de SHA-256); doorgeven vermijdt een
            // tweede hash-pass in getPageFiltered op het hot path.
            return getPageFiltered(key, page, pageSize, ontvanger, afzender, map)
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
                    // De ongefilterde list-cache bewaart de volledige `Bericht`-JSON-blob (incl. inhoud
                    // en bijlagen), zodat ook `update` deze in-place kan herschrijven. Voor de
                    // publieke lijst-respons projecteren we naar samenvatting; zo behoudt de
                    // BerichtenPage één uniform element-type met het RediSearch-pad.
                    // try/catch op JsonProcessingException: cache-data niet deserialiseerbaar duidt
                    // op schema-drift of corruptie. Aparte log + rethrow zodat dit niet als
                    // "Redis onbereikbaar" wegfiltert in het generieke onFailure-pad; de resource
                    // heeft een eigen 500-pad voor JsonProcessingException.
                    val berichten = try {
                        jsonList
                            .map { objectMapper.readValue(it, Bericht::class.java) }
                            .map { it.toSamenvatting() }
                    } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
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

    private fun getPageFiltered(cacheKey: String, page: Int, pageSize: Int, ontvanger: Identificatienummer, afzender: String?, map: String?): Uni<BerichtenPage?> {
        // ontvanger is altijd een TAG-filter; afzender en map zijn optioneel en worden met
        // spaties (AND) gecombineerd in de RediSearch-query.
        val query = buildList {
            add("@ontvanger:{${escapeTag(ontvanger.waarde)}}")

            if (!afzender.isNullOrBlank()) add("@afzender:{${escapeTag(afzender)}}")

            if (!map.isNullOrBlank()) add("@map:{${escapeTag(map)}}")
        }.joinToString(" ")

        val offset = page * pageSize
        val queryArgs = samenvattingQueryArgs()
            .limit(offset, pageSize)
            .sortByDescending("publicatietijdstip")

        return redis.search().ftSearch(BerichtenCache.SEARCH_INDEX, query, queryArgs)
            .map { response ->
                val berichten = response.documents().map { doc -> documentToSamenvatting(doc) }
                val total = response.count().toLong()
                if (total == 0L && berichten.isEmpty()) {
                    null
                } else {
                    val totalPages = if (total == 0L) 0 else ((total + pageSize - 1) / pageSize).toInt()
                    BerichtenPage(berichten, page, pageSize, total, totalPages)
                }
            }
            .call { result -> renewReadTtlSamenvatting(cacheKey, result?.berichten) }
            // afzender is user-input zonder spec-pattern in alle gevallen (zie OpenAPI);
            // .length voorkomt log-injectie via CRLF in een rogue query-param.
            .onFailure().invoke { e -> log.errorf(e, "RediSearch getPageFiltered mislukt (key=%s, afzender.length=%d, map.length=%d)", cacheKey, afzender?.length ?: 0, map?.length ?: 0) }
    }

    override fun search(ontvanger: Identificatienummer, q: String, page: Int, pageSize: Int, afzender: String?, map: String?): Uni<BerichtenPage> {
        val cacheKey = BerichtenCache.cacheKey(ontvanger)
        val ontvangerFilter = "@ontvanger:{${escapeTag(ontvanger.waarde)}}"
        val escapedQ = escapeRedisSearch(q)
        val afzenderFilter = if (afzender != null) " @afzender:{${escapeTag(afzender)}}" else ""
        val mapFilter = if (!map.isNullOrBlank()) " @map:{${escapeTag(map)}}" else ""
        val query = "$ontvangerFilter (@onderwerp:$escapedQ)$afzenderFilter$mapFilter".trim()

        val offset = page * pageSize
        val queryArgs = samenvattingQueryArgs()
            .limit(offset, pageSize)
            .sortByDescending("publicatietijdstip")

        return redis.search().ftSearch(BerichtenCache.SEARCH_INDEX, query, queryArgs)
            .map { response ->
                val berichten = response.documents().map { doc -> documentToSamenvatting(doc) }
                val total = response.count().toLong()
                val totalPages = if (total == 0L) 0 else ((total + pageSize - 1) / pageSize).toInt()
                BerichtenPage(berichten, page, pageSize, total, totalPages)
            }
            .call { result -> renewReadTtlSamenvatting(cacheKey, result.berichten) }
            // Splitsing zodat document-corruptie niet als query-fout in metrics belandt.
            // q.length i.p.v. q-waarde: q is user-controlled (RediSearch-query) en zou
            // log-injectie via CRLF kunnen veroorzaken.
            .onFailure(CacheCorruptedException::class.java).invoke { e ->
                log.errorf(e, "Cache-document corrupt bij search (key=%s, q.length=%d)", cacheKey, q.length)
            }
            .onFailure { it !is CacheCorruptedException }.invoke { e ->
                log.errorf(e, "RediSearch query mislukt (key=%s, q.length=%d)", cacheKey, q.length)
            }
    }

    // Beperk de FT.SEARCH-projectie tot de samenvatting-velden: de lijst-/zoek-respons heeft
    // `inhoud`/`bijlagen` niet nodig, dus het is verspilling om die — potentieel grote —
    // velden over de wire op te halen. `documentToSamenvatting` mapt naar het lichte
    // [BerichtSamenvatting]-type. De detail-lookup (`getById`) gebruikt de hash en blijft volledig.
    private fun samenvattingQueryArgs(): QueryArgs {
        val args = QueryArgs()
        SAMENVATTING_VELDEN.forEach { args.returnAttribute(it) }
        return args
    }

    override fun getById(berichtId: UUID, ontvanger: Identificatienummer): Uni<Bericht?> {
        val cacheKey = BerichtenCache.cacheKey(ontvanger)
        val berichtKey = BerichtenCache.berichtKey(berichtId)
        return redis.hash(String::class.java).hgetall(berichtKey)
            .map { fields ->
                if (fields.isEmpty()) return@map null
                val bericht = hashToBericht(fields)
                if (bericht.ontvanger != ontvanger.waarde) null else bericht
            }
            .call { bericht ->
                if (bericht != null) renewReadTtl(cacheKey, listOf(bericht))
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
     *
     * Caller geeft de `cacheKey` mee — vermijdt een tweede SHA-256-pass per read op
     * de hot path (callers hebben de key al berekend).
     */
    private fun renewReadTtl(cacheKey: String, berichten: List<Bericht>?): Uni<Void> {
        if (berichten.isNullOrEmpty()) return renewSessionTtl(cacheKey)
        return renewBerichtTtls(cacheKey, berichten.map { it.berichtId })
    }

    /** Variant van [renewReadTtl] voor samenvatting-paden (FT.SEARCH-projecties). */
    private fun renewReadTtlSamenvatting(cacheKey: String, berichten: List<BerichtSamenvatting>?): Uni<Void> {
        if (berichten.isNullOrEmpty()) return renewSessionTtl(cacheKey)
        return renewBerichtTtls(cacheKey, berichten.map { it.berichtId })
    }

    /**
     * MULTI/EXEC pipeline-batch: alle EXPIRE-commands (2 sessie-keys + N bericht-hashes) in één
     * round-trip i.p.v. losse calls. Op pageSize=100 scheelt dit 100+ RTT's.
     * Log + slik: TTL-renew is best-effort. Bij stille discard zou een Redis-storing in de batch
     * ongezien blijven; de read zelf is al gelukt.
     */
    private fun renewBerichtTtls(cacheKey: String, ids: List<UUID>): Uni<Void> {
        val listKey = listKey(cacheKey)
        val statusKey = statusKey(cacheKey)
        return redis.withTransaction { tx ->
            val txKey = tx.key()
            val expires = mutableListOf<Uni<Void>>()
            expires.add(txKey.expire(listKey, ttl))
            expires.add(txKey.expire(statusKey, ttl))
            ids.forEach { id -> expires.add(txKey.expire(BerichtenCache.berichtKey(id), ttl)) }
            Uni.join().all(expires).andFailFast().replaceWithVoid()
        }.replaceWithVoid()
            .onFailure().invoke { e -> log.warnf(e, "Sliding TTL renewReadTtl mislukt voor cacheKey=%s (read geslaagd, TTL niet verlengd)", cacheKey) }
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
        // Alle hieronder verplichte velden worden onvoorwaardelijk geschreven door `berichtToHash`;
        // ontbreken duidt op een corrupte/partiële hash-entry. We wrappen ontbrekende velden,
        // onleesbare UUID/Instant/Int én Jackson-parseerfouten in CacheCorruptedException zodat
        // het oproep-pad dit niet als "Redis onbereikbaar" wegfiltert maar als 500 surfacet
        // (de resource heeft een dedicated CacheCorruptedException-pad).
        // `bijlagen` en `map` zijn écht optioneel: lege lijst en null betekenen "geen" — niet "corrupt".
        fun required(name: String): String = fields[name]
            ?: throw CacheCorruptedException.veldOntbreekt(name)

        val berichtIdStr = required("berichtId")

        return Bericht(
            berichtId = try {
                UUID.fromString(berichtIdStr)
            } catch (ex: IllegalArgumentException) {
                throw CacheCorruptedException.onleesbareWaarde("berichtId", ex)
            },
            afzender = required("afzender"),
            ontvanger = required("ontvanger"),
            onderwerp = required("onderwerp"),
            inhoud = required("inhoud"),
            publicatietijdstip = try {
                Instant.parse(required("publicatietijdstip"))
            } catch (ex: java.time.format.DateTimeParseException) {
                throw CacheCorruptedException.onleesbareWaarde("publicatietijdstip", ex)
            },
            magazijnId = required("magazijnId"),
            aantalBijlagen = try {
                required("aantalBijlagen").toInt()
            } catch (ex: NumberFormatException) {
                throw CacheCorruptedException.onleesbareWaarde("aantalBijlagen", ex)
            },
            bijlagen = fields["bijlagen"]?.let {
                try {
                    objectMapper.readValue(it, BIJLAGE_LIST_TYPE)
                } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
                    throw CacheCorruptedException.onleesbareWaarde("bijlagen", ex)
                }
            } ?: emptyList(),
            map = fields["map"],
            status = fields["status"]?.let {
                try {
                    Leesstatus.fromWire(it)
                } catch (ex: IllegalArgumentException) {
                    throw CacheCorruptedException.onleesbareWaarde("status", ex)
                }
            },
        )
    }

    /**
     * Maakt een [BerichtSamenvatting] uit een FT.SEARCH-document. Bevat alleen de samenvatting-
     * velden uit [SAMENVATTING_VELDEN]; `inhoud` en `bijlagen` worden bewust niet geprojecteerd.
     * Ontbrekende samenvatting-kernvelden duiden op corruptie — geen fallback-defaults.
     */
    private fun documentToSamenvatting(doc: io.quarkus.redis.datasource.search.Document): BerichtSamenvatting {
        val berichtId = doc.property("berichtId").asString()
        return BerichtSamenvatting(
            berichtId = UUID.fromString(berichtId),
            afzender = doc.property("afzender").asString(),
            ontvanger = doc.property("ontvanger").asString(),
            onderwerp = doc.property("onderwerp").asString(),
            publicatietijdstip = Instant.parse(doc.property("publicatietijdstip").asString()),
            magazijnId = doc.property("magazijnId").asString(),
            aantalBijlagen = doc.property("aantalBijlagen")?.asString()?.toInt()
                ?: error("samenvatting-document mist 'aantalBijlagen' (corrupt entry) berichtId=$berichtId"),
            map = doc.property("map")?.asString(),
            status = doc.property("status")?.asString()?.let { Leesstatus.fromWire(it) },
        )
    }

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

    override fun updateBerichtMetadata(berichtId: UUID, ontvanger: Identificatienummer, status: String?, map: String?): Uni<Bericht?> {
        // Merge-PATCH: alleen de meegegeven velden worden bijgewerkt; ontbrekende velden blijven
        // onveranderd. De sessie-`list` bevat volledige JSON-blobs die het ongefilterde `getPage`
        // direct deserialiseert — een HSET-alleen update laat die list stale (oude status/map
        // zichtbaar in `GET /berichten` tot TTL). We schrijven daarom óók de matchende list-entry
        // terug, onder hetzelfde optimistic locking als `delete` (WATCH op list- en bericht-key):
        // een concurrente `createBericht`/`delete` tussen read en EXEC breekt de transactie af zodat
        // we geen lost-update veroorzaken. Bij afbreken herhalen we tot een klein plafond; daarna
        // laten we de cache met rust en levert de operatie een retriable 503 op zodat de client
        // opnieuw kan proberen.
        val leesstatus = status?.let { Leesstatus.fromWire(it) }
        return probeerUpdateMetadata(berichtId, ontvanger, leesstatus, map, 1)
    }

    private fun probeerUpdateMetadata(
        berichtId: UUID,
        ontvanger: Identificatienummer,
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

                            if (bericht.ontvanger != ontvanger.waarde) {
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

            if (result.discarded() && poging < MAX_UPDATE_METADATA_POGINGEN) {
                probeerUpdateMetadata(berichtId, ontvanger, status, map, poging + 1)
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

    override fun createBericht(bericht: Bericht, ontvanger: Identificatienummer): Uni<Void> {
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
            .onFailure().invoke { e -> log.errorf(e, "Redis createBericht mislukt voor berichtId=%s", bericht.berichtId) }
    }

    override fun delete(berichtId: UUID, ontvanger: Identificatienummer): Uni<Void> {
        // Idempotent cache-invalidate. De sessie-`list` bevat JSON-blobs (gevuld via
        // `createBericht.rpush`) die `getPage` direct deserialiseert — dus na DEL op de hash
        // blijft het bericht zonder list-prune zichtbaar in `GET /berichten`.
        //
        // Aanpak: HGETALL (eigenaar-check) → LRANGE + parse om de matchende blob(s) op
        // `berichtId` te vinden → LREM met die exact-value(s) → DEL hash. LREM is per-call
        // Redis-atomair en raakt alleen exact-matchende entries: een concurrent `createBericht`
        // tussen onze LRANGE en LREM voegt een blob met andere inhoud toe (andere berichtId
        // ⇒ andere JSON) en blijft dus behouden zonder WATCH/retry-loop. Bij eerdere TTL
        // van 60s was een WATCH+retry-aanpak met "self-healing via TTL" als noodverbetering
        // verdedigbaar; bij de huidige TTL (uren) is dat niet meer acceptabel — LREM
        // elimineert de race volledig in plaats van hem af te wachten.
        val berichtKey = BerichtenCache.berichtKey(berichtId)
        val cacheKey = BerichtenCache.cacheKey(ontvanger)
        val listKey = listKey(cacheKey)

        return redis.hash(String::class.java).hgetall(berichtKey)
            .chain { fields ->
                if (fields.isEmpty() || fields["ontvanger"] != ontvanger.waarde) {
                    Uni.createFrom().voidItem()
                } else {
                    pruneListEnDelHash(listKey, berichtKey, berichtId)
                }
            }
            .onFailure().invoke { e -> log.errorf(e, "Redis delete mislukt voor berichtId=%s", berichtId) }
    }

    private fun pruneListEnDelHash(listKey: String, berichtKey: String, berichtId: UUID): Uni<Void> =
        redis.list(String::class.java).lrange(listKey, 0, -1)
            .chain { entries ->
                val matching = entries.filter { json -> blobMatchtBerichtId(json, berichtId) }
                val lremAll = if (matching.isEmpty()) {
                    Uni.createFrom().voidItem()
                } else {
                    // Eén LREM per match (in praktijk 0 of 1, want berichtId is uniek).
                    // count=0: verwijder alle exact-matchende voorkomens.
                    val lremUnis = matching.map { blob ->
                        redis.list(String::class.java).lrem(listKey, 0, blob)
                    }
                    Uni.join().all(lremUnis).andFailFast().replaceWithVoid()
                }

                lremAll.chain { _ -> redis.key().del(berichtKey).replaceWithVoid() }
            }

    private fun blobMatchtBerichtId(json: String, berichtId: UUID): Boolean =
        runCatching { objectMapper.readTree(json).get("berichtId")?.asText() }
            .onFailure { e ->
                // Corrupte/legacy list-entry: conservatief behouden (kan per definitie niet
                // het te verwijderen bericht zijn), maar wel loggen — een onparsebare blob
                // duidt op cache-corruptie.
                log.warnf(e, "Onparsebare list-entry bij delete-prune; entry overgeslagen. berichtId=%s", berichtId)
            }
            .getOrNull() == berichtId.toString()

    companion object {
        // Aantal optimistic-lock-pogingen voor `updateBerichtMetadata` voordat de invalidate
        // wordt opgegeven; concurrente wijziging op één sessie-list is zeldzaam, dus een klein
        // plafond volstaat en voorkomt ongebonden retry onder pathologische contentie. (Delete
        // gebruikt LREM en heeft geen retry-loop nodig.)
        private const val MAX_UPDATE_METADATA_POGINGEN = 5

        private fun listKey(key: String) = "$key:list"
        private fun statusKey(key: String) = "$key:status"
        private fun lockKey(key: String) = "$key:lock"

        // TypeReference voor Jackson-deserialisatie van de `bijlagen`-hash-field (JSON-array).
        private val BIJLAGE_LIST_TYPE = object : TypeReference<List<BijlageSamenvatting>>() {}

        // Hash-velden die [BerichtSamenvatting] nodig heeft; gebruikt als FT.SEARCH RETURN-lijst
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
