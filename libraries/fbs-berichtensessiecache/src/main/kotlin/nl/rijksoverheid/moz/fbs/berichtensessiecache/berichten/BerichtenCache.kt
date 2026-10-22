package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.search.CreateArgs
import io.quarkus.redis.datasource.search.FieldType
import io.quarkus.redis.datasource.search.QueryArgs
import io.quarkus.redis.datasource.transactions.OptimisticLockingTransactionResult
import io.quarkus.redis.datasource.transactions.ReactiveTransactionalRedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.Uni
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieConfig
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.dataTtl
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.kern.identificatie.IdentificatienummerType
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
 * de facade (BlockingSessiecache) een retriable 503 geeft i.p.v. een misleidende 404 (na een
 * geslaagde magazijn-write zou een 404 de client laten denken dat het bericht weg is).
 */
internal class CacheContentieException(sleutel: BerichtSleutel) :
    RuntimeException("Cache-update afgebroken door gelijktijdige wijziging voor $sleutel")

internal interface BerichtenCache {
    /** Schrijft [berichten] als de lijst van [key], zonder naar een lopende ronde te kijken. */
    fun store(key: String, berichten: List<Bericht>): Uni<Void>

    /** Schrijft een afsluitende status en geeft de lock vrij, wie hem ook houdt. */
    fun storeAggregationStatus(key: String, status: AggregationStatus): Uni<Void>

    /**
     * Neemt de lock voor ophaalronde [ronde] en zet de BEZIG-status; `false` als er al een ronde
     * loopt. [ronde] is uniek per ronde: alleen wie de lock nog met dat token houdt, mag hierna
     * schrijven ([updateAggregationStatus], [slaRondeOp], [sluitRondeAf]).
     */
    fun trySetAggregationStatus(key: String, status: AggregationStatus, ronde: String): Uni<Boolean>

    /** Werkt de BEZIG-status bij, alleen zolang [ronde] de lock houdt. */
    fun updateAggregationStatus(key: String, status: AggregationStatus, ronde: String): Uni<Void>

    /**
     * Slaat het resultaat van ronde [ronde] op (lijst, berichthashes en [status]) en geeft de lock
     * vrij, in één transactie die alleen doorgaat als [ronde] de lock dan nog houdt. `false` als dat
     * niet zo is: de sessie eindigde intussen (het opruimen wist de lock) of de lock verliep. Dan is
     * er niets geschreven, zodat een latere sessie nooit de resultaten van een eerdere ronde ziet.
     */
    fun slaRondeOp(key: String, berichten: List<Bericht>, status: AggregationStatus, ronde: String): Uni<Boolean>

    /**
     * Schrijft de afsluitende [status] van een mislukte ronde en geeft de lock vrij, alleen als
     * [ronde] hem nog houdt; `false` als dat niet zo is en er niets geschreven is.
     */
    fun sluitRondeAf(key: String, status: AggregationStatus, ronde: String): Uni<Boolean>

    fun getAggregationStatus(key: String): Uni<AggregationStatus?>
    fun getPage(key: String, page: Int, pageSize: Int, afzender: String? = null, ontvanger: Identificatienummer? = null, map: String? = null): Uni<BerichtenPagina?>
    fun search(ontvanger: Identificatienummer, q: String, page: Int, pageSize: Int, afzender: String? = null, map: String? = null): Uni<BerichtenPagina>
    fun getById(sleutel: BerichtSleutel, ontvanger: Identificatienummer): Uni<Bericht?>
    fun updateBerichtMetadata(sleutel: BerichtSleutel, ontvanger: Identificatienummer, status: String?, map: String?): Uni<Bericht?>
    fun createBericht(bericht: Bericht, ontvanger: Identificatienummer): Uni<Void>
    fun delete(sleutel: BerichtSleutel, ontvanger: Identificatienummer): Uni<Void>

    /**
     * Of [ontvanger] dit bericht binnen deze sessie zélf verwijderde. Alleen te vertrouwen als
     * antwoord op een misser: staat het bericht er nog, dan is dat de waarheid en zegt de
     * tombstone niets. `false` betekent "niet bekend als verwijderd", niet "bestaat nog".
     */
    fun isVerwijderdVoor(sleutel: BerichtSleutel, ontvanger: Identificatienummer): Uni<Boolean>

    companion object {
        // ThreadLocal MessageDigest + HexFormat: bespaart `getInstance("SHA-256")`-allocatie
        // én per-byte `String.format("%02x", ...)` per cacheKey-call. cacheKey wordt per
        // request meerdere keren aangeroepen (getBerichten, getById, createBericht, etc.).
        private val SHA256_DIGEST: ThreadLocal<MessageDigest> = ThreadLocal.withInitial {
            MessageDigest.getInstance("SHA-256")
        }
        private val HEX = HexFormat.of()

        /** Voorvoegsel van elke sleutel onder [cacheKey]; daarachter staat het [kenmerk]. */
        const val SLEUTEL_PREFIX = "berichtensessiecache:v4:"

        fun cacheKey(ontvanger: Identificatienummer): String = SLEUTEL_PREFIX + kenmerk(ontvanger)

        /**
         * SHA-256 over de canonieke ontvanger, hex. Een pseudoniem en geen anonimisering: een BSN
         * is terug te rekenen, dus ook dit hoort niet in een log.
         */
        fun kenmerk(ontvanger: Identificatienummer): String {
            val canonical = ontvanger.toCanonicalString()
            val digest = SHA256_DIGEST.get().apply { reset() }
                .digest(canonical.toByteArray(Charsets.UTF_8))
            return HEX.formatHex(digest)
        }

        // v4: de berichthash draagt het magazijn, want een berichtnummer is alleen uniek binnen
        // het magazijn dat het uitdeelde. Een pod van een vorige versie zou een gedeelde lijst
        // op alleen het nummer matchen en zo het bericht van de ene organisatie bij de andere
        // bijwerken of opruimen; daarom zijn alle sleutels van de sessie disjunct van v3.
        fun berichtKey(sleutel: BerichtSleutel) = "$BERICHT_PREFIX${sleutel.magazijnId}:${sleutel.berichtId}"
        const val BERICHT_PREFIX = "bericht:v4:"

        /**
         * Key van de tombstone die een zelf-verwijderd bericht achterlaat, onder de sessie-key van
         * de ontvanger. Twee redenen voor die vorm. De eigenaar zit in de key en niet in de waarde,
         * dus is de tombstone eigenaar-gescopeerd zonder dat er een identificatienummer in Redis
         * belandt — [cacheKey] is een SHA-256. En de key valt buiten [BERICHT_PREFIX], het
         * indexeerpatroon van [SEARCH_INDEX], zodat een tombstone niet als zoekresultaat terugkomt.
         */
        fun verwijderdKey(ontvanger: Identificatienummer, sleutel: BerichtSleutel) =
            "${cacheKey(ontvanger)}:verwijderd:${sleutel.magazijnId}:${sleutel.berichtId}"

        // De index-naam draagt dezelfde versie als de prefix waarop hij filtert, en dat is geen
        // cosmetica: de bootstrap laat een bestaande index bewust ongemoeid, dus een index die op
        // een oudere prefix is aangemaakt zou blijven staan terwijl alle nieuwe hashes elders
        // landen. Filter- en zoekqueries geven dan stil nul resultaten — een index op de
        // verkeerde prefix is functioneel identiek aan géén index, maar valt buiten de fail-fast
        // hieronder. Met de versie in de naam maakt elke nieuwe pod zijn eigen index aan en
        // blijven oude pods tijdens een rolling deploy op de oude werken.
        const val SEARCH_INDEX = "berichten-idx-v4"
    }
}

@ApplicationScoped
internal class RedisBerichtenCache(
    private val redis: ReactiveRedisDataSource,
    private val objectMapper: ObjectMapper,
    sessieConfig: SessieConfig,
    // Korte, aparte vangnet-TTL voor de aggregatie-lock én de BEZIG-status, losgekoppeld van de
    // TTL op de gegevens. Crasht een pod midden in aggregatie, dan mag de ontvanger niet langer
    // dan deze timeout geblokkeerd blijven voor opnieuw `_ophalen`.
    // Moet ruim boven de normale aggregatieduur (resolver outer-await + cache-await) liggen, anders
    // ontgrendelt een nog lopende aggregatie voortijdig en kan een concurrent `_ophalen` dubbel draaien.
    @param:ConfigProperty(name = "berichtensessiecache.aggregation-lock-ttl", defaultValue = "PT2M")
    private val aggregationLockTtl: Duration,
    // Begrenzing op de blocking-await van de RediSearch-bootstrap bij startup (presence-check
    // `ft_list` + index-aanmaak `ftCreate`). Beide raken een lokale/naast-de-pod Redis, dus
    // dezelfde 5s-ondergrens als de overige cache-awaits. Overschrijden → fail-fast bij startup.
    @param:ConfigProperty(name = "berichtensessiecache.startup-redisearch-timeout-seconds", defaultValue = "5")
    private val startupRedisearchTimeoutSeconds: Long,
    // Begrenst het aantal Redis-commando's dat tegelijk in de connection-wachtrij staat
    // (`quarkus.redis.max-waiting-handlers`, default 2048). Zonder deze grens groeit een
    // store-transactie mee met het aantal organisaties van de ontvanger en loopt die wachtrij
    // vol — de ophaalronde faalt dan pas in de laatste stap, ná alle bevragingen. Elk bericht
    // kost twee commando's (HSET + PEXPIRE), maar die zijn per bericht met `.chain` geregen, dus
    // de piek per batch is deze waarde zelf, niet het dubbele.
    @param:ConfigProperty(name = "berichtensessiecache.redis-batchgrootte", defaultValue = "256")
    private val redisBatchgrootte: Int,
    // Los binnengehaald i.p.v. hardgecodeerd, zodat `init()` de invariant met redisBatchgrootte
    // kan bewaken ook wanneer een operator alleen de sessiecache-sleutel aanpast. De sleutel
    // hoort bij de Redis-client-extensie, niet bij deze library — vandaar dat hij hier via
    // `@ConfigProperty` binnenkomt in plaats van een eigen sessiecache-configuratienaam te krijgen.
    @param:ConfigProperty(name = "quarkus.redis.max-waiting-handlers", defaultValue = "2048")
    private val maxWaitingHandlers: Int,
) : BerichtenCache {
    private val log = Logger.getLogger(RedisBerichtenCache::class.java)

    // Vast, niet verschuivend met leesverkeer: het wissen is een actieve stap bij het einde van de
    // laatste sessie, deze TTL is alleen het vangnet als dat mislukt.
    private val dataTtl: Duration = sessieConfig.dataTtl

    @PostConstruct
    fun init() {
        // Moet > 0: een 0/negatieve waarde maakt `atMost(ZERO)` onbegrensd en zou de
        // startup-bescherming stil uitschakelen. Vóór de Redis-calls zodat de guard ook
        // zonder bereikbare Redis aanslaat.
        require(startupRedisearchTimeoutSeconds > 0) {
            "berichtensessiecache.startup-redisearch-timeout-seconds " +
                "($startupRedisearchTimeoutSeconds) moet groter zijn dan 0"
        }

        // Moet > 0: 0 laat `chunked` pas bij de eerste store werpen, midden in een ophaalronde,
        // met een melding die de configuratiesleutel niet noemt.
        require(redisBatchgrootte > 0) {
            "berichtensessiecache.redis-batchgrootte ($redisBatchgrootte) moet groter zijn dan 0"
        }

        // Moet < maxWaitingHandlers: de piek per batch is redisBatchgrootte (HSET en EXPIRE zijn
        // per bericht met `.chain` geregen, dus staan nooit tegelijk in de wachtrij). Zonder deze
        // guard kan een operator de batchgrootte via de omgeving optrekken zonder dat er iets
        // waarschuwt, en faalt een store pas in de laatste stap van een ophaalronde — ná alle
        // bevragingen — met een melding die geen van beide configuratiesleutels noemt.
        require(redisBatchgrootte < maxWaitingHandlers) {
            "berichtensessiecache.redis-batchgrootte ($redisBatchgrootte) moet kleiner zijn dan " +
                "quarkus.redis.max-waiting-handlers ($maxWaitingHandlers)"
        }

        val startupTimeout = Duration.ofSeconds(startupRedisearchTimeoutSeconds)

        // Idempotente bootstrap: meerdere pods delen één Redis-cluster. Bestaande index
        // NIET droppen — dat laat queries van andere replicas tijdelijk falen. Schema-
        // wijzigingen lopen via de handmatige operations-procedure: docs/operations/redisearch-schema-bump.md.
        // Presence-check via FT._LIST i.p.v. drop-en-catch (foutmelding-tekst is geen API-contract).
        val bestaandeIndexen = try {
            redis.search().ft_list().await().atMost(startupTimeout)
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
                .indexedField("ontvangerType", FieldType.TAG)
                .indexedField("publicatietijdstip", FieldType.TAG)
                .indexedField("map", FieldType.TAG)
            redis.search().ftCreate(BerichtenCache.SEARCH_INDEX, args)
                .await().atMost(startupTimeout)
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
        val lijst = LijstOpslag(key, berichten)

        if (berichten.isEmpty()) {
            return redis.key().del(lijst.listKey).replaceWithVoid()
        }

        return redis.withTransaction { tx -> schrijfLijst(tx, lijst) }
            .replaceWithVoid()
            .invoke { _ -> log.debugf("Opgeslagen %d berichten in cache", berichten.size) }
            .onFailure().invoke { e -> log.errorf(e, "Redis store mislukt voor key=%s", key) }
    }

    override fun slaRondeOp(key: String, berichten: List<Bericht>, status: AggregationStatus, ronde: String): Uni<Boolean> {
        val lijst = LijstOpslag(key, berichten)
        val lockKey = lockKey(key)
        val statusJson = objectMapper.writeValueAsString(status)

        // WATCH op de lock: wist het opruimen hem tussen de controle en EXEC, of neemt een volgende
        // ronde hem, dan breekt Redis de transactie af en is er niets geschreven.
        return redis.withTransaction<Boolean>(
            { reads -> reads.value(String::class.java).get(lockKey).map { it == ronde } },
            { eigenaar, tx ->
                if (eigenaar) {
                    schrijfLijst(tx, lijst)
                        .chain { _ -> tx.value(String::class.java).set(statusKey(key), statusJson, SetArgs().px(dataTtl)) }
                        .chain { _ -> tx.key().del(lockKey) }
                        .replaceWithVoid()
                } else {
                    Uni.createFrom().voidItem()
                }
            },
            lockKey,
        ).map { resultaat -> !resultaat.discarded() && resultaat.preTransactionResult }
            .invoke { opgeslagen ->
                if (opgeslagen) {
                    log.debugf("Opgeslagen %d berichten in cache", berichten.size)
                } else {
                    log.infof("Ophaalronde was de lock kwijt (sessie-einde of verlopen lock); resultaten niet opgeslagen")
                }
            }
            .onFailure().invoke { e -> log.errorf("Redis slaRondeOp mislukt (fout=%s)", e.javaClass.name) }
    }

    /** De lijst, de berichthashes en de sleutelset; zonder berichten alleen het wissen van de lijst. */
    private fun schrijfLijst(tx: ReactiveTransactionalRedisDataSource, lijst: LijstOpslag): Uni<Void> {
        val txKey = tx.key()

        if (lijst.sorted.isEmpty()) return txKey.del(lijst.listKey).replaceWithVoid()

        val txHash = tx.hash(String::class.java)

        // De hashes van een vorige ronde blijven staan en dus ook in de sleutelset: alleen zo kan
        // het opruimen na de laatste sessie ze nog vinden.
        return txKey.del(lijst.listKey)
            .chain { _ -> tx.list(String::class.java).rpush(lijst.listKey, *lijst.jsonValues.toTypedArray()) }
            .chain { _ -> txKey.pexpire(lijst.listKey, dataTtl) }
            .chain { _ ->
                RedisBatching.inBatches(lijst.sorted, redisBatchgrootte) { bericht ->
                    val berichtKey = BerichtenCache.berichtKey(bericht.sleutel)

                    txHash.hset(berichtKey, berichtToHash(bericht))
                        .chain { _ -> txKey.pexpire(berichtKey, dataTtl) }
                        .replaceWithVoid()
                }
            }
            .chain { _ -> tx.set(String::class.java).sadd(lijst.sleutelsKey, *lijst.berichtKeys.toTypedArray()) }
            .chain { _ -> txKey.pexpire(lijst.sleutelsKey, dataTtl) }
            .replaceWithVoid()
    }

    private inner class LijstOpslag(key: String, berichten: List<Bericht>) {
        val listKey = listKey(key)
        val sleutelsKey = sleutelsKey(key)
        val sorted = berichten.sortedByDescending { it.publicatietijdstip }
        val jsonValues = sorted.map { objectMapper.writeValueAsString(it) }
        val berichtKeys = sorted.map { BerichtenCache.berichtKey(it.sleutel) }
    }

    override fun storeAggregationStatus(key: String, status: AggregationStatus): Uni<Void> {
        val statusKey = statusKey(key)
        val json = objectMapper.writeValueAsString(status)
        return redis.value(String::class.java).set(statusKey, json, SetArgs().px(dataTtl))
            .chain { _ -> redis.key().del(lockKey(key)) }
            .replaceWithVoid()
            .onFailure().invoke { e -> log.errorf(e, "Redis storeAggregationStatus mislukt voor key=%s", key) }
    }

    override fun sluitRondeAf(key: String, status: AggregationStatus, ronde: String): Uni<Boolean> {
        val json = objectMapper.writeValueAsString(status)

        return voerLockScriptUit(ZET_STATUS_ALS_EIGENAAR, key, ronde, json, dataTtl.toMillis().toString(), "1")
            .onFailure().invoke { e -> log.errorf("Redis sluitRondeAf mislukt (fout=%s)", e.javaClass.name) }
    }

    override fun updateAggregationStatus(key: String, status: AggregationStatus, ronde: String): Uni<Void> {
        val json = objectMapper.writeValueAsString(status)
        // Nog-lopende (BEZIG) status: korte vangnet-TTL gelijk aan de lock, zodat een crash de
        // status niet 12u laat hangen. De terminale GEREED/FOUT-status volgt wél de TTL van de
        // gegevens, omdat die het gecachte resultaat spiegelt.
        return voerLockScriptUit(ZET_STATUS_ALS_EIGENAAR, key, ronde, json, aggregationLockTtl.toMillis().toString(), "0")
            .replaceWithVoid()
            .onFailure().invoke { e -> log.errorf(e, "Redis updateAggregationStatus mislukt voor key=%s", key) }
    }

    override fun trySetAggregationStatus(key: String, status: AggregationStatus, ronde: String): Uni<Boolean> {
        val lockKey = lockKey(key)
        val statusKey = statusKey(key)

        // SET NX EX in één commando: atomair — geen tussenliggend venster waarbij de lock
        // bestaat zonder TTL (wat bij losse SETNX + EXPIRE zou kunnen optreden als EXPIRE faalt).
        val lockArgs = SetArgs().nx().ex(aggregationLockTtl.seconds)

        return redis.value(String::class.java).setAndChanged(lockKey, ronde, lockArgs)
            .chain { wasSet ->
                if (wasSet) {
                    // Lazy JSON-serialisatie: alleen serializen als lock daadwerkelijk genomen is.
                    // Bij hoge concurrent-409-druk bespaart dit Jackson-werk voor afgewezen requests.
                    val json = objectMapper.writeValueAsString(status)
                    // statusKey-fout na geslaagde lock-set: rollback de lock via best-effort vrijgeven,
                    // anders blijft de ontvanger op de aggregatie-lock-TTL hangen voor wat een transient
                    // Redis-blip was. De BEZIG-status deelt diezelfde korte vangnet-TTL als de lock.
                    redis.value(String::class.java).setex(statusKey, aggregationLockTtl.seconds, json)
                        .onFailure().call { _ ->
                            voerLockScriptUit(GEEF_LOCK_VRIJ, key, ronde)
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

    /** KEYS: lock, status. ARGV: het ronde-token, daarna wat [script] verwacht. Antwoord 1 als de ronde de lock hield. */
    private fun voerLockScriptUit(script: String, key: String, ronde: String, vararg argumenten: String): Uni<Boolean> =
        redis.execute("EVAL", script, "2", lockKey(key), statusKey(key), ronde, *argumenten)
            .map { it.toInteger() == 1 }

    override fun getAggregationStatus(key: String): Uni<AggregationStatus?> {
        val statusKey = statusKey(key)
        return redis.value(String::class.java).get(statusKey)
            .map { json -> json?.let { objectMapper.readValue(it, AggregationStatus::class.java) } }
            .onFailure().invoke { e -> log.errorf(e, "Redis getAggregationStatus mislukt voor key=%s", key) }
    }

    override fun getPage(key: String, page: Int, pageSize: Int, afzender: String?, ontvanger: Identificatienummer?, map: String?): Uni<BerichtenPagina?> {
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
                    // De ongefilterde list-cache bewaart de volledige `Bericht`-JSON-blob (incl.
                    // bijlage-handles), zodat ook `update` deze in-place kan herschrijven. Voor de
                    // publieke lijst-respons projecteren we naar samenvatting; zo behoudt de
                    // BerichtenPagina één uniform element-type met het RediSearch-pad.
                    // try/catch op JsonProcessingException: cache-data niet deserialiseerbaar duidt
                    // op schema-drift of corruptie. Aparte log + rethrow zodat dit niet als
                    // "Redis onbereikbaar" wegfiltert in het generieke onFailure-pad; de facade
                    // (BlockingSessiecache) heeft een eigen 500-pad voor JsonProcessingException.
                    val berichten = try {
                        jsonList.map { objectMapper.readValue(it, Bericht::class.java).toSamenvatting() }
                    } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
                        // Log de fout-soort, NIET de exception zelf: Jackson zet bij
                        // INCLUDE_SOURCE_IN_LOCATION (default aan) het ruwe JSON-fragment in de
                        // message — dat bevat BSN/RSIN. PII mag nooit in de log; key +
                        // exception-klasse volstaan voor diagnose (corruptie/schema-drift).
                        log.errorf("Cache-bericht niet deserialiseerbaar voor key=%s (corruptie of schema-drift); fout=%s", key, ex.javaClass.name)
                        throw ex
                    }
                    val totalPages = ((total + pageSize - 1) / pageSize).toInt()
                    BerichtenPagina(
                        berichten = berichten,
                        page = page,
                        pageSize = pageSize,
                        totalElements = total,
                        totalPages = totalPages,
                    )
                }
            }
            .onFailure(com.fasterxml.jackson.core.JsonProcessingException::class.java)
                .invoke { _ -> /* al gelogd hierboven, geen dubbele log */ }
            .onFailure { ex -> ex !is com.fasterxml.jackson.core.JsonProcessingException }
                .invoke { e -> log.errorf(e, "Redis getPage mislukt voor key=%s, page=%d", key, page) }
    }

    private fun getPageFiltered(cacheKey: String, page: Int, pageSize: Int, ontvanger: Identificatienummer, afzender: String?, map: String?): Uni<BerichtenPagina?> {
        // ontvanger is altijd een TAG-filter; afzender en map zijn optioneel en worden met
        // spaties (AND) gecombineerd in de RediSearch-query.
        val query = buildList {
            add("@ontvanger:{${escapeTag(ontvanger.waarde)}}")
            add("@ontvangerType:{${escapeTag(ontvanger.type.name)}}")

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
                    BerichtenPagina(berichten, page, pageSize, total, totalPages)
                }
            }
            // afzender is user-input zonder spec-pattern in alle gevallen (zie OpenAPI);
            // .length voorkomt log-injectie via CRLF in een rogue query-param.
            .onFailure().invoke { e -> log.errorf(e, "RediSearch getPageFiltered mislukt (key=%s, afzender.length=%d, map.length=%d)", cacheKey, afzender?.length ?: 0, map?.length ?: 0) }
    }

    override fun search(ontvanger: Identificatienummer, q: String, page: Int, pageSize: Int, afzender: String?, map: String?): Uni<BerichtenPagina> {
        val cacheKey = BerichtenCache.cacheKey(ontvanger)
        val ontvangerFilter =
            "@ontvanger:{${escapeTag(ontvanger.waarde)}} @ontvangerType:{${escapeTag(ontvanger.type.name)}}"
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
                BerichtenPagina(berichten, page, pageSize, total, totalPages)
            }
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

    // Beperk de FT.SEARCH-projectie tot de samenvatting-velden: de lijst-/zoek-respons draagt
    // `bijlagen` niet, dus dat veld hoeft niet over de wire. `documentToSamenvatting` mapt naar
    // het lichte [BerichtSamenvatting]-type; de detail-lookup (`getById`) gebruikt de hash en
    // blijft volledig.
    private fun samenvattingQueryArgs(): QueryArgs {
        val args = QueryArgs()
        SAMENVATTING_VELDEN.forEach { args.returnAttribute(it) }
        return args
    }

    override fun getById(sleutel: BerichtSleutel, ontvanger: Identificatienummer): Uni<Bericht?> {
        val berichtKey = BerichtenCache.berichtKey(sleutel)
        return redis.hash(String::class.java).hgetall(berichtKey)
            .map { fields ->
                if (fields.isEmpty()) return@map null
                val bericht = hashToBericht(fields)
                if (bericht.ontvanger != ontvanger) null else bericht
            }
            .onFailure().invoke { e -> log.errorf(e, "Redis getById mislukt voor %s", sleutel) }
    }

    private fun berichtToHash(bericht: Bericht): Map<String, String> = buildMap {
        put("berichtId", bericht.berichtId.toString())
        put("afzender", bericht.afzender)
        put("afzenderNaam", bericht.afzenderNaam)
        put("ontvanger", bericht.ontvanger.waarde)
        put("ontvangerType", bericht.ontvanger.type.name)
        put("onderwerp", bericht.onderwerp)
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

    /**
     * Herbouwt het getypeerde [Identificatienummer] uit opgeslagen `type` + `waarde` en
     * hervalideert daarbij (elfproef/lengte). Een onbekende type-naam of invariant-schending
     * duidt op cache-corruptie → [CacheCorruptedException] (500), niet "Redis onbereikbaar".
     * `DomainValidationException` is een `IllegalArgumentException`, dus één catch volstaat
     * voor zowel `valueOf` (onbekend type) als `of` (elfproef/lengte).
     */
    private fun reconstrueerOntvanger(waarde: String, typeNaam: String): Identificatienummer =
        try {
            Identificatienummer.of(IdentificatienummerType.valueOf(typeNaam), waarde)
        } catch (ex: IllegalArgumentException) {
            throw CacheCorruptedException.onleesbareWaarde("ontvanger", ex)
        }

    private fun hashToBericht(fields: Map<String, String>): Bericht {
        // Alle hieronder verplichte velden worden onvoorwaardelijk geschreven door `berichtToHash`;
        // ontbreken duidt op een corrupte/partiële hash-entry. We wrappen ontbrekende velden,
        // onleesbare UUID/Instant/Int én Jackson-parseerfouten in CacheCorruptedException zodat
        // het oproep-pad dit niet als "Redis onbereikbaar" wegfiltert maar als 500 surfacet
        // (de facade heeft een dedicated CacheCorruptedException-pad).
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
            afzenderNaam = required("afzenderNaam"),
            ontvanger = reconstrueerOntvanger(required("ontvanger"), required("ontvangerType")),
            onderwerp = required("onderwerp"),
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
     * velden uit [SAMENVATTING_VELDEN]; `bijlagen` wordt bewust niet geprojecteerd.
     * Ontbrekende samenvatting-kernvelden duiden op corruptie — geen fallback-defaults.
     */
    private fun documentToSamenvatting(doc: io.quarkus.redis.datasource.search.Document): BerichtSamenvatting {
        // Ontbrekende of onparsebare velden wikkelen we — net als hashToBericht — in
        // CacheCorruptedException, zodat corruptie op het zoek-/filterpad als eigen
        // data-issue (500) surfacet en niet als "Redis onbereikbaar" (503) wegfiltert.
        fun required(name: String): String = doc.property(name)?.asString()
            ?: throw CacheCorruptedException.veldOntbreekt(name)

        val berichtIdStr = required("berichtId")

        return BerichtSamenvatting(
            berichtId = try {
                UUID.fromString(berichtIdStr)
            } catch (ex: IllegalArgumentException) {
                throw CacheCorruptedException.onleesbareWaarde("berichtId", ex)
            },
            afzender = required("afzender"),
            afzenderNaam = required("afzenderNaam"),
            ontvanger = reconstrueerOntvanger(required("ontvanger"), required("ontvangerType")),
            onderwerp = required("onderwerp"),
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
            map = doc.property("map")?.asString(),
            status = doc.property("status")?.asString()?.let {
                try {
                    Leesstatus.fromWire(it)
                } catch (ex: IllegalArgumentException) {
                    throw CacheCorruptedException.onleesbareWaarde("status", ex)
                }
            },
        )
    }

    // Plan dat de read-fase (onder WATCH) doorgeeft aan de write-fase van de update-transactie.
    private sealed interface UpdatePlan {
        // Hash ontbreekt of hoort bij een andere ontvanger → resultaat null (geen mutatie).
        data object Geen : UpdatePlan

        // Niets te schrijven (status==null && map==null): retourneer het ongewijzigde bericht
        // zonder write, zodat we geen onnodige TTL-renew of list-rewrite forceren.
        data class Ongewijzigd(val bericht: Bericht) : UpdatePlan

        // Te schrijven en te wissen hash-velden + de herbouwde list met het vervangen blob op
        // (magazijnId, berichtId). Wissen is een HDEL: een leeg veld zou de TAG-index een lege map laten zien.
        data class Wijzig(
            val updated: Bericht,
            val hashVelden: Map<String, String>,
            val gewisteVelden: List<String>,
            val nieuweLijst: List<String>,
        ) : UpdatePlan
    }

    override fun updateBerichtMetadata(sleutel: BerichtSleutel, ontvanger: Identificatienummer, status: String?, map: String?): Uni<Bericht?> {
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
        return probeerUpdateMetadata(sleutel, ontvanger, leesstatus, map, 1)
    }

    private fun probeerUpdateMetadata(
        sleutel: BerichtSleutel,
        ontvanger: Identificatienummer,
        status: Leesstatus?,
        map: String?,
        poging: Int,
    ): Uni<Bericht?> {
        val berichtKey = BerichtenCache.berichtKey(sleutel)
        val cacheKey = BerichtenCache.cacheKey(ontvanger)
        val listKey = listKey(cacheKey)

        return redis.withTransaction<UpdatePlan>(
            { reads -> bepaalUpdatePlan(reads, berichtKey, listKey, sleutel, ontvanger, status, map) },
            { plan, tx -> voerUpdatePlanUit(plan, tx, berichtKey, listKey, sleutelsKey(cacheKey)) },
            listKey,
            berichtKey,
        ).chain { result ->
            verwerkUpdateResultaat(result, sleutel, ontvanger, status, map, poging)
        }
            .onFailure().invoke { e ->
                // Contentie is al op warn gelogd en is geen echte storing; alleen
                // overige fouten als error loggen.
                if (e !is CacheContentieException) log.errorf(e, "Redis update mislukt voor %s", sleutel)
            }
    }

    /** Read-fase (onder WATCH): bepaalt wat de write-fase moet schrijven. */
    private fun bepaalUpdatePlan(
        reads: ReactiveRedisDataSource,
        berichtKey: String,
        listKey: String,
        sleutel: BerichtSleutel,
        ontvanger: Identificatienummer,
        status: Leesstatus?,
        map: String?,
    ): Uni<UpdatePlan> =
        reads.hash(String::class.java).hgetall(berichtKey)
            .chain { fields ->
                if (fields.isEmpty()) {
                    Uni.createFrom().item(UpdatePlan.Geen)
                } else {
                    val bericht = hashToBericht(fields)

                    when {
                        bericht.ontvanger != ontvanger -> Uni.createFrom().item(UpdatePlan.Geen)
                        status == null && map == null -> Uni.createFrom().item(UpdatePlan.Ongewijzigd(bericht))
                        else -> maakWijzigPlan(reads, listKey, sleutel, bericht, status, map)
                    }
                }
            }

    private fun maakWijzigPlan(
        reads: ReactiveRedisDataSource,
        listKey: String,
        sleutel: BerichtSleutel,
        bericht: Bericht,
        status: Leesstatus?,
        map: String?,
    ): Uni<UpdatePlan> {
        val wistMap = map == Sessiecache.MAP_WISSEN
        val updated = bericht.copy(
            status = status ?: bericht.status,
            map = if (wistMap) null else map ?: bericht.map,
        )

        val hashVelden = buildMap {
            status?.let { put("status", it.wire) }
            map?.takeUnless { wistMap }?.let { put("map", it) }
        }

        val gewisteVelden = if (wistMap) listOf("map") else emptyList()
        val updatedJson = objectMapper.writeValueAsString(updated)

        return reads.list(String::class.java).lrange(listKey, 0, -1)
            .map { entries ->
                UpdatePlan.Wijzig(updated, hashVelden, gewisteVelden, herbouwLijst(entries, sleutel, updatedJson))
            }
    }

    /** Vervangt het blob van [sleutel] in de sessie-list door [updatedJson]; overige entries blijven. */
    private fun herbouwLijst(entries: List<String>, sleutel: BerichtSleutel, updatedJson: String): List<String> =
        entries.map { json -> if (blobMatcht(json, sleutel, "update")) updatedJson else json }

    /** Write-fase (MULTI/EXEC): voert het in de read-fase bepaalde plan uit. */
    private fun voerUpdatePlanUit(
        plan: UpdatePlan,
        tx: ReactiveTransactionalRedisDataSource,
        berichtKey: String,
        listKey: String,
        sleutelsKey: String,
    ): Uni<Void> =
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
                        .chain { _ -> txKey.pexpire(listKey, dataTtl) }
                        .replaceWithVoid()
                } else {
                    Uni.createFrom().voidItem()
                }

                // HSET weigert een lege veldenset; die ontstaat als de patch alleen een map wist.
                val zetVelden = if (plan.hashVelden.isNotEmpty()) {
                    txHash.hset(berichtKey, plan.hashVelden).replaceWithVoid()
                } else {
                    Uni.createFrom().voidItem()
                }

                val wisVelden = if (plan.gewisteVelden.isNotEmpty()) {
                    txHash.hdel(berichtKey, *plan.gewisteVelden.toTypedArray()).replaceWithVoid()
                } else {
                    Uni.createFrom().voidItem()
                }

                // Ook de sleutelset: krijgt de hash een nieuwe TTL en de set niet, dan kan de set
                // eerder verlopen en vindt het opruimen de hash niet meer.
                zetVelden
                    .chain { _ -> wisVelden }
                    .chain { _ -> txKey.pexpire(berichtKey, dataTtl) }
                    .chain { _ -> tx.set(String::class.java).sadd(sleutelsKey, berichtKey) }
                    .chain { _ -> txKey.pexpire(sleutelsKey, dataTtl) }
                    .chain { _ -> rebuildList }
                    .replaceWithVoid()
            }
        }

    private fun verwerkUpdateResultaat(
        result: OptimisticLockingTransactionResult<UpdatePlan>,
        sleutel: BerichtSleutel,
        ontvanger: Identificatienummer,
        status: Leesstatus?,
        map: String?,
        poging: Int,
    ): Uni<Bericht?> {
        if (result.discarded() && poging < MAX_UPDATE_METADATA_POGINGEN) {
            return probeerUpdateMetadata(sleutel, ontvanger, status, map, poging + 1)
        }

        if (result.discarded()) {
            // Contentie is géén not-found: een null zou de facade-caller laten 404'en
            // terwijl het bericht bestaat (en de magazijn-write al geslaagd kan
            // zijn). Signaleer een retriable fout → 503 zodat de client opnieuw
            // kan proberen. Op warn: verwacht onder hoge gelijktijdigheid, geen crash.
            log.warnf(
                "Redis update na %d pogingen afgebroken door concurrente wijziging; retriable 503. %s",
                poging,
                sleutel,
            )

            return Uni.createFrom().failure(CacheContentieException(sleutel))
        }

        return when (val plan = result.preTransactionResult) {
            UpdatePlan.Geen -> Uni.createFrom().nullItem<Bericht>()
            is UpdatePlan.Ongewijzigd -> Uni.createFrom().item(plan.bericht)
            is UpdatePlan.Wijzig -> Uni.createFrom().item(plan.updated)
        }
    }

    /**
     * Voegt niets toe als het bericht al in de lijst van deze sessie staat. Dat gebeurt gewoon: het
     * magazijn meldt een bericht aan uit zijn wachtrij, en een ophaalronde die intussen liep, heeft
     * het rechtstreeks uit het magazijn al meegenomen. Zonder deze controle staat het er daarna
     * twee keer in.
     *
     * Controleren en toevoegen in één Lua-script, dus atomair zonder WATCH. Een WATCH op de lijst
     * zou afbreken op elke EXPIRE die een open berichtenbox op zijn hartslag doet — precies wat er
     * gebeurt als een reeks aanmeldingen binnenkomt terwijl die ontvanger volgt.
     */
    override fun createBericht(bericht: Bericht, ontvanger: Identificatienummer): Uni<Void> {
        val cacheKey = BerichtenCache.cacheKey(ontvanger)
        val berichtKey = BerichtenCache.berichtKey(bericht.sleutel)
        val velden = berichtToHash(bericht).flatMap { (veld, waarde) -> listOf(veld, waarde) }
        val argumenten = listOf(
            AANMELD_SCRIPT,
            "3",
            listKey(cacheKey),
            berichtKey,
            sleutelsKey(cacheKey),
            bericht.berichtId.toString(),
            bericht.magazijnId,
            objectMapper.writeValueAsString(bericht),
            dataTtl.toMillis().toString(),
        ) + velden

        return redis.execute("EVAL", *argumenten.toTypedArray())
            .invoke { uitkomst -> log.debugf("Aangemeld bericht %s: %s", bericht.sleutel, uitkomst) }
            .replaceWithVoid()
            .onFailure().invoke { e -> log.errorf(e, "Redis createBericht mislukt voor %s", bericht.sleutel) }
    }

    override fun delete(sleutel: BerichtSleutel, ontvanger: Identificatienummer): Uni<Void> {
        // Idempotent cache-invalidate. De sessie-`list` bevat JSON-blobs (gevuld via
        // `createBericht.rpush`) die `getPage` direct deserialiseert — dus na DEL op de hash
        // blijft het bericht zonder list-prune zichtbaar in `GET /berichten`.
        //
        // Aanpak: HGETALL (eigenaar-check) → LRANGE + parse om de matchende blob(s) op
        // (magazijnId, berichtId) te vinden → LREM met die exact-value(s) → DEL hash. LREM is
        // per-call Redis-atomair en raakt alleen exact-matchende entries: een concurrent `createBericht`
        // tussen onze LRANGE en LREM voegt een blob met andere inhoud toe (ander bericht
        // ⇒ andere JSON) en blijft dus behouden zonder WATCH/retry-loop. Bij eerdere TTL
        // van 60s was een WATCH+retry-aanpak met "self-healing via TTL" als noodverbetering
        // verdedigbaar; bij de huidige TTL (uren) is dat niet meer acceptabel — LREM
        // elimineert de race volledig in plaats van hem af te wachten.
        val berichtKey = BerichtenCache.berichtKey(sleutel)
        val cacheKey = BerichtenCache.cacheKey(ontvanger)
        val listKey = listKey(cacheKey)

        return redis.hash(String::class.java).hgetall(berichtKey)
            .chain { fields ->
                if (fields.isEmpty() ||
                    fields["ontvanger"] != ontvanger.waarde ||
                    fields["ontvangerType"] != ontvanger.type.name
                ) {
                    // Twee gevallen tegelijk: er is geen hash (tweede DELETE, of de TTL liep af),
                    // of de hash is van een ándere ontvanger. In het eerste geval valt de eigenaar
                    // niet meer vast te stellen, in het tweede is de aanroeper niet de eigenaar —
                    // in geen van beide hoort hier een tombstone te ontstaan. Een tombstone van een
                    // eerdere DELETE blijft staan; die wordt hier niet aangeraakt.
                    Uni.createFrom().voidItem()
                } else {
                    pruneListEnDelHash(listKey, berichtKey, sleutel)
                        .chain { _ -> schrijfTombstone(sleutel, ontvanger) }
                }
            }
            .onFailure().invoke { e -> log.errorf(e, "Redis delete mislukt voor %s", sleutel) }
    }

    override fun isVerwijderdVoor(sleutel: BerichtSleutel, ontvanger: Identificatienummer): Uni<Boolean> =
        redis.key().exists(BerichtenCache.verwijderdKey(ontvanger, sleutel))
            // Degradeert naar "niet bekend als verwijderd": de tombstone verrijkt een misser, hij
            // is er geen voorwaarde voor. Zonder deze terugval maakt een Redis-hik op déze lookup
            // van een doodgewone 404 een 503 met `Retry-After`, en gaat de client wachten op een
            // bericht dat nooit bestond.
            .onFailure().invoke { e -> log.warnf(e, "Tombstone-lookup mislukt; misser blijft onbekend. %s", sleutel) }
            .onFailure().recoverWithItem(false)

    /**
     * Laat achter dat déze ontvanger dit bericht verwijderde, zodat een volgende lookup het
     * verschil kan maken tussen "nooit gezien" en "zelf weggegooid".
     *
     * `SET … PX` in één commando, net als de aggregatie-lock hierboven: bij een losse SET gevolgd
     * door EXPIRE blijft de key voorgoed staan zodra die tweede stap faalt. De waarde is een
     * plaatshouder — de eigenaar zit in de key (zie [BerichtenCache.verwijderdKey]). In dezelfde
     * transactie gaat de key de sleutelset in, zodat het opruimen na de laatste sessie hem vindt.
     */
    private fun schrijfTombstone(sleutel: BerichtSleutel, ontvanger: Identificatienummer): Uni<Void> {
        val tombstone = BerichtenCache.verwijderdKey(ontvanger, sleutel)
        val sleutelsKey = sleutelsKey(BerichtenCache.cacheKey(ontvanger))

        return redis.withTransaction { tx ->
            tx.value(String::class.java).set(tombstone, "1", SetArgs().px(dataTtl))
                .chain { _ -> tx.set(String::class.java).sadd(sleutelsKey, tombstone) }
                .chain { _ -> tx.key().pexpire(sleutelsKey, dataTtl) }
        }
            // Best-effort: de delete zelf is dan al onherroepelijk
            // uitgevoerd, en die alsnog laten omvallen levert een 502 op een geslaagde verwijdering
            // plus een compensatie-invalidate die niets meer te invalideren heeft. Wat verloren
            // gaat is het kenmerk: de rest van de sessie meldt "onbekend" waar "verwijderd" hoorde.
            // Eigen alert-anker, want de generieke delete-logregel zou beweren dat de delete faalde.
            .onFailure().invoke { e ->
                log.errorf(
                    e,
                    "%s tombstone-schrijf faalde ná geslaagde cache-delete; een volgende lookup meldt onbekend i.p.v. verwijderd. %s",
                    ALERT_TOMBSTONE_VERLOREN,
                    sleutel,
                )
            }
            .onFailure().recoverWithNull()
            .replaceWithVoid()
    }

    private fun pruneListEnDelHash(listKey: String, berichtKey: String, sleutel: BerichtSleutel): Uni<Void> =
        redis.list(String::class.java).lrange(listKey, 0, -1)
            .chain { entries ->
                val matching = entries.filter { json -> blobMatcht(json, sleutel, "delete-prune") }

                // Eén LREM per match (in praktijk 0 of 1, want het paar is uniek). count=0:
                // verwijder alle exact-matchende voorkomens.
                RedisBatching.inBatches(matching, redisBatchgrootte) { blob ->
                    redis.list(String::class.java).lrem(listKey, 0, blob).replaceWithVoid()
                }.chain { _ -> redis.key().del(berichtKey).replaceWithVoid() }
            }

    /**
     * Of het blob [sleutel] is: berichtnummer én magazijn, want alleen het paar wijst één bericht aan.
     * Een onparsebaar blob kan per definitie niet het bedoelde bericht zijn: het blijft staan, maar
     * wordt wel gelogd — het duidt op cache-corruptie.
     */
    private fun blobMatcht(json: String, sleutel: BerichtSleutel, handeling: String): Boolean =
        runCatching { objectMapper.readTree(json) }
            .onFailure { e -> log.warnf(e, "Onparsebare list-entry bij %s; entry behouden. %s", handeling, sleutel) }
            .getOrNull()
            ?.let { blob ->
                blob.path("berichtId").asText() == sleutel.berichtId.toString() &&
                    blob.path("magazijnId").asText() == sleutel.magazijnId
            } == true

    companion object {
        // Aantal optimistic-lock-pogingen voor `updateBerichtMetadata` voordat de update een
        // retriable contentie-fout geeft. Een klein plafond voorkomt ongebonden retry onder
        // pathologische contentie. Ook een EXPIRE van een gevolgde berichtenbox breekt de WATCH af.
        // (Delete gebruikt LREM en heeft geen retry-loop nodig.)
        // TODO(MinBZK/MijnOverheidZakelijk#1166): atomair zonder WATCH, zoals createBericht, zodat meelezen niet meer botst.
        private const val MAX_UPDATE_METADATA_POGINGEN = 5

        /**
         * KEYS: sessie-lijst, berichthash, sleutelset. ARGV: berichtId, magazijnId, JSON voor de
         * lijst, TTL in ms, daarna de hash-velden als veld/waarde-paren. Een treffer vraagt beide:
         * hetzelfde nummer van een ander magazijn is een ander bericht. Elke geschreven hash gaat de
         * sleutelset in, zodat het opruimen na de laatste sessie hem vindt.
         *
         * Kent de lijst het bericht maar is de hash verlopen, dan komt de hash terug met status en
         * map uit de lijst-entry: alleen die twee wijzigen na het aanmelden, en de aanmelding zelf
         * zou een al gelezen bericht weer ongelezen maken. Een onleesbare entry telt niet als
         * treffer, net als in het leespad.
         *
         * Redis voert een script uit zonder iets anders te doen, dus de lus moet goedkoop blijven:
         * alleen een entry waar het id letterlijk in staat, wordt als JSON gelezen.
         */
        private val AANMELD_SCRIPT = """
            local gevonden = nil
            for _, entry in ipairs(redis.call('LRANGE', KEYS[1], 0, -1)) do
                if string.find(entry, ARGV[1], 1, true) then
                    local leesbaar, bericht = pcall(cjson.decode, entry)
                    if leesbaar and type(bericht) == 'table' and bericht.berichtId == ARGV[1] and bericht.magazijnId == ARGV[2] then
                        gevonden = bericht
                        break
                    end
                end
            end

            local ttl = tonumber(ARGV[4])

            local function registreer()
                redis.call('PEXPIRE', KEYS[2], ttl)
                redis.call('SADD', KEYS[3], KEYS[2])
                redis.call('PEXPIRE', KEYS[3], ttl)
            end

            if gevonden == nil then
                redis.call('RPUSH', KEYS[1], ARGV[3])
                redis.call('PEXPIRE', KEYS[1], ttl)
                redis.call('HSET', KEYS[2], unpack(ARGV, 5))
                registreer()
                return 'toegevoegd'
            end

            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 'al aanwezig'
            end

            local velden = {}
            for i = 5, #ARGV, 2 do
                if ARGV[i] ~= 'status' and ARGV[i] ~= 'map' then
                    table.insert(velden, ARGV[i])
                    table.insert(velden, ARGV[i + 1])
                end
            end
            for _, veld in ipairs({ 'status', 'map' }) do
                if type(gevonden[veld]) == 'string' then
                    table.insert(velden, veld)
                    table.insert(velden, gevonden[veld])
                end
            end

            redis.call('HSET', KEYS[2], unpack(velden))
            registreer()
            return 'hash hersteld'
        """.trimIndent()

        /**
         * Schrijft een status voor de ronde die de lock houdt. ARGV: ronde-token, status-JSON, TTL in
         * ms, en of de lock daarna vrijkomt (1) of blijft (0). Compare-and-set in één script: een
         * losse GET en SET laat het opruimen en een volgende ronde ertussen.
         */
        private val ZET_STATUS_ALS_EIGENAAR = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end

            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
            if ARGV[4] == '1' then
                redis.call('DEL', KEYS[1])
            end
            return 1
        """.trimIndent()

        /** Geeft de lock alleen vrij als de ronde uit ARGV[1] hem houdt: compare-and-delete. */
        private val GEEF_LOCK_VRIJ = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end

            redis.call('DEL', KEYS[1])
            return 1
        """.trimIndent()

        // Stabiel alert-anker (los van vertaalbare proza) voor de Loki-rule die moet alarmeren
        // wanneer het verwijderd-kenmerk stil wegvalt. Wijzig de waarde niet zonder de
        // bijbehorende alert-rule mee te verhuizen.
        private const val ALERT_TOMBSTONE_VERLOREN = "FBS_ALERT[tombstone_verloren]"

        internal fun listKey(key: String) = "$key:list"
        internal fun statusKey(key: String) = "$key:status"
        internal fun lockKey(key: String) = "$key:lock"

        /** SET met de berichthashes en tombstones van de ontvanger; het opruimen wist ze aan de hand hiervan. */
        internal fun sleutelsKey(key: String) = "$key:sleutels"

        // TypeReference voor Jackson-deserialisatie van de `bijlagen`-hash-field (JSON-array).
        private val BIJLAGE_LIST_TYPE = object : TypeReference<List<BijlageSamenvatting>>() {}

        // Hash-velden die [BerichtSamenvatting] nodig heeft; gebruikt als FT.SEARCH RETURN-lijst.
        // `bijlagen` blijft eruit omdat de samenvatting het niet draagt, niet omdat het groot is.
        internal val SAMENVATTING_VELDEN = listOf(
            "berichtId",
            "afzender",
            "afzenderNaam",
            "ontvanger",
            "ontvangerType",
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

/**
 * De uitkomst van een ophaalronde zoals die de sessie overleeft. [nietOpgehaald] telt de
 * organisaties die door de gelijktijdigheidsgrens van de uitvraag niet bevraagd zijn; die horen
 * niet bij [mislukt], want er is geen uitspraak over dat magazijn gedaan.
 *
 * Een status die vóór dit veld in de cache is geschreven leest terug met `nietOpgehaald = 0` — de
 * default hierboven. Dat klopt inhoudelijk: zo'n ronde kende de categorie nog niet.
 *
 * [afgekapt] noemt organisaties die geslaagd zijn maar niet alles leverden; ze tellen dus mee in
 * [geslaagd] en kunnen niet ook in [nietGeleverd] staan.
 */
internal data class AggregationStatus(
    val status: OphalenStatus = OphalenStatus.GEREED,
    val totaalMagazijnen: Int = 0,
    val geslaagd: Int = 0,
    val mislukt: Int = 0,
    val nietOpgehaald: Int = 0,
    val nietGeleverd: List<NietGeleverd> = emptyList(),
    val afgekapt: List<Afgekapt> = emptyList(),
) {
    init {
        require(totaalMagazijnen >= 0) { "totaalMagazijnen mag niet negatief zijn" }
        require(geslaagd >= 0) { "geslaagd mag niet negatief zijn" }
        require(mislukt >= 0) { "mislukt mag niet negatief zijn" }
        require(nietOpgehaald >= 0) { "nietOpgehaald mag niet negatief zijn" }
        require(geslaagd + mislukt + nietOpgehaald <= totaalMagazijnen) {
            "geslaagd + mislukt + nietOpgehaald mag niet groter zijn dan totaalMagazijnen"
        }

        // Kleiner mag: een status die vóór dit veld bestond, draagt de tellers maar niet de lijst.
        // [volledigheid] geeft dat verschil door, zodat het portaal het als onbekend kan tonen.
        require(nietGeleverd.size <= mislukt + nietOpgehaald) {
            "nietGeleverd mag niet meer organisaties noemen dan mislukt + nietOpgehaald"
        }

        require(nietGeleverd.distinctBy { it.magazijnId }.size == nietGeleverd.size) {
            "nietGeleverd noemt een organisatie dubbel"
        }

        require(afgekapt.size <= geslaagd) { "afgekapt mag niet meer organisaties noemen dan geslaagd" }
        require(afgekapt.distinctBy { it.magazijnId }.size == afgekapt.size) { "afgekapt noemt een organisatie dubbel" }

        require(afgekapt.none { a -> nietGeleverd.any { it.magazijnId == a.magazijnId } }) {
            "een organisatie kan niet tegelijk afgekapt en niet geleverd zijn"
        }
    }

    /** Wat de lijst van deze ronde over zijn eigen volledigheid moet zeggen. Een functie, zodat hij niet in Redis belandt. */
    fun volledigheid(): Volledigheid = Volledigheid(mislukt + nietOpgehaald, nietGeleverd, afgekapt)
}

internal enum class OphalenStatus {
    BEZIG,
    GEREED,
    FOUT,
}
