package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.core.JsonProcessingException
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.TimeoutException
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClientFactory
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnFault
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResolver
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResponseOverflow
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResult
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.ConnectException
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@ApplicationScoped
class BerichtensessiecacheService(
    private val berichtenCache: BerichtenCache,
    private val clientFactory: MagazijnClientFactory,
    private val resolver: MagazijnResolver,
    @param:ConfigProperty(name = "profiel.resolver.inner-timeout-seconds", defaultValue = "18")
    private val innerTimeoutSeconds: Long,
    @param:ConfigProperty(name = "profiel.resolver.outer-await-seconds", defaultValue = "25")
    private val outerAwaitSeconds: Long,
    // Heap-bescherming tegen rogue magazijn. Belt-and-suspenders naast
    // `quarkus.http.limits.max-body-size`. Niet stilzwijgend verhogen zonder load-evidence.
    @param:ConfigProperty(name = "berichtensessiecache.max-berichten-per-magazijn", defaultValue = "200")
    private val maxBerichtenPerMagazijn: Int,
) {
    private val log = Logger.getLogger(BerichtensessiecacheService::class.java)

    @PostConstruct
    fun valideerTimeouts() {
        // Outer-budget MOET groter zijn dan inner zodat de inner-timeout altijd eerst
        // aanslaat. Anders verliest de caller de juiste foutclassificatie: een outer
        // j.u.c.TimeoutException wordt nu door het [ProfielServiceFoutException.resolverMislukt]
        // pad geclassificeerd als "resolver hangt", niet als "Profiel-service traag".
        require(outerAwaitSeconds > innerTimeoutSeconds) {
            "profiel.resolver.outer-await-seconds ($outerAwaitSeconds) moet groter zijn dan " +
                "profiel.resolver.inner-timeout-seconds ($innerTimeoutSeconds)"
        }

        log.infof(
            "Profiel-resolver timeouts: inner=%ds outer=%ds",
            innerTimeoutSeconds,
            outerAwaitSeconds,
        )
    }

    fun getBerichten(page: Int, pageSize: Int, ontvanger: Identificatienummer, afzender: String?): Uni<BerichtenPage> {
        log.debugf("Ophalen berichten uit cache: page=%d, pageSize=%d", page, pageSize)
        val key = BerichtenCache.cacheKey(ontvanger)

        return berichtenCache.getPage(key, page, pageSize, afzender, ontvanger)
            .map { it ?: BerichtenPage(emptyList(), page, pageSize, 0L, 0) }
    }

    fun getAggregationStatus(ontvanger: Identificatienummer): Uni<AggregationStatus?> {
        val key = BerichtenCache.cacheKey(ontvanger)

        return berichtenCache.getAggregationStatus(key)
    }

    fun getBerichtById(berichtId: UUID, ontvanger: Identificatienummer): Uni<Bericht?> {
        log.debugf("Ophalen bericht uit cache: %s", berichtId)

        return berichtenCache.getById(berichtId, ontvanger)
    }

    fun zoekBerichten(q: String, page: Int, pageSize: Int, ontvanger: Identificatienummer, afzender: String?): Uni<BerichtenPage> {
        log.debugf("Zoeken berichten via RediSearch: q=%s, page=%d, pageSize=%d", q, page, pageSize)

        return berichtenCache.search(ontvanger, q, page, pageSize, afzender)
    }

    fun updateBerichtStatus(berichtId: UUID, ontvanger: Identificatienummer, status: String): Uni<Bericht?> {
        log.debugf("Bijwerken berichtstatus: berichtId=%s, status=%s", berichtId, status)

        return berichtenCache.updateStatus(berichtId, ontvanger, status)
    }

    fun addBericht(bericht: Bericht, ontvanger: Identificatienummer): Uni<Bericht> {
        log.debugf("Toevoegen bericht aan cache: berichtId=%s", bericht.berichtId)

        return berichtenCache.addBericht(bericht, ontvanger).replaceWith(bericht)
    }

    /**
     * Orkestreert het ophalen van berichten uit de magazijnen die de [MagazijnResolver]
     * voor deze ontvanger relevant acht (voorkeur-gestuurd voor BSN/RSIN/KVK, alle voor
     * OIN-B2B), slaat ze op in de cache, en retourneert een SSE-compatible Multi met
     * statusevents per magazijn.
     */
    fun ophalenBerichten(ontvanger: Identificatienummer): Multi<MagazijnEvent> {
        val cacheKey = BerichtenCache.cacheKey(ontvanger)

        val bezigStatus = AggregationStatus(
            status = OphalenStatus.BEZIG,
            totaalMagazijnen = 0,
        )

        // Atomaire lock via trySetAggregationStatus (SET NX EX in één commando): voorkom
        // concurrent ophalen voor dezelfde ontvanger. Blokkerende await() is hier bewust:
        // het aanroepende endpoint (BerichtenOphalenResource) is @Blocking gemarkeerd. De
        // lock-check moet synchroon afgerond zijn voordat de Multi-stream gestart wordt,
        // omdat de 409-response anders niet meer mogelijk is.
        // Try/catch: bij Redis-fout (timeout, connection drop) kan de lock-set partial
        // geslaagd zijn (lockKey wel, statusKey niet). De cache-laag compenseert intern,
        // maar caller-side cleanup als defense-in-depth voor await-level failures.
        val wasSet = try {
            berichtenCache.trySetAggregationStatus(cacheKey, bezigStatus)
                .await().atMost(Duration.ofSeconds(5))
        } catch (ex: Exception) {
            // Cause-walking: Mutiny's blocking-await wrapt upstream-failures, dus directe
            // instanceof matched de echte fout niet.
            val errorId = UUID.randomUUID()
            val classification = classifyLockAcquireError(ex)

            when (classification) {
                LockAcquireError.INTERRUPTED -> {
                    // Herstel interrupt-flag voor graceful pod-shutdown; 503 (transient), geen eigen-bug.
                    Thread.currentThread().interrupt()
                    log.warnf(ex, "(errorId=%s) Lock-acquire onderbroken voor key=%s", errorId, cacheKey)
                    cleanupLockMetFoutStatus(cacheKey, "lock-acquire interrupted", errorId)
                    throw WebApplicationException("Service shutdown tijdens ophaalstart", 503)
                }
                LockAcquireError.JSON_SERIALIZATION -> {
                    log.errorf(ex, "(errorId=%s) Lock-acquire JSON-serialisatie-fout voor key=%s", errorId, cacheKey)
                    cleanupLockMetFoutStatus(cacheKey, "json-serialisatie-fout bij lock-acquire", errorId)
                    throw WebApplicationException("Interne fout bij serialisatie status", 500)
                }
                LockAcquireError.TIMEOUT -> {
                    log.errorf(ex, "(errorId=%s) Lock-acquire timeout voor key=%s", errorId, cacheKey)
                    cleanupLockMetFoutStatus(cacheKey, "lock-acquire timeout", errorId)
                    throw WebApplicationException("Cache niet bereikbaar bij ophaalstart (timeout)", 503)
                }
                LockAcquireError.IO_FAULT -> {
                    log.errorf(ex, "(errorId=%s) Lock-acquire I/O-fout voor key=%s (cause=%s)", errorId, cacheKey, ex.javaClass.simpleName)
                    cleanupLockMetFoutStatus(cacheKey, "lock-acquire I/O-fout: ${ex.javaClass.simpleName}", errorId)
                    throw WebApplicationException("Cache niet bereikbaar bij ophaalstart", 503)
                }
                LockAcquireError.UNEXPECTED -> {
                    log.errorf(ex, "(errorId=%s) Lock-acquire onverwachte fout voor key=%s (cause=%s)", errorId, cacheKey, ex.javaClass.simpleName)
                    cleanupLockMetFoutStatus(cacheKey, "lock-acquire onverwacht: ${ex.javaClass.simpleName}", errorId)
                    throw WebApplicationException("Interne fout bij ophaalstart", 500)
                }
            }
        }

        if (!wasSet) {
            throw WebApplicationException(
                "Berichten worden momenteel al opgehaald voor deze ontvanger. Wacht tot het ophalen is afgerond.",
                409,
            )
        }

        val resolvedIds = try {
            // Outer-budget = inner-timeout + marge (cross-validatie in valideerTimeouts),
            // zodat de outer nooit aanslaat vóór de inner — anders verliest de caller
            // de juiste foutclassificatie (timeout vs onbereikbaar).
            resolver.resolve(ontvanger).await().atMost(Duration.ofSeconds(outerAwaitSeconds))
        } catch (ex: ProfielServiceFoutException) {
            // ex.errorId doorgeven zodat cleanup-log dezelfde id draagt als mapper-respons.
            cleanupLockMetFoutStatus(cacheKey, "profiel-service-fout: ${ex.categorie.name}", ex.errorId)
            // CONFIG_DRIFT: eigen-config-fout, niet Profiel-storing — emit zichtbare
            // OPHALEN_FOUT i.p.v. 503 zodat client weet "geen ophaling mogelijk", niet
            // "Profiel offline, retry over 30s". Cache wordt NIET overschreven met empty.
            if (ex.categorie == ProfielServiceFoutException.Categorie.CONFIG_DRIFT) {
                // errorId in foutmelding zodat support de cleanup-log + resolver-log kan
                // correleren (cleanupLockMetFoutStatus gebruikt dezelfde ex.errorId).
                return Multi.createFrom().item(
                    MagazijnEvent(
                        event = EventType.OPHALEN_FOUT,
                        totaalMagazijnen = 0,
                        foutmelding = "Geen ophaling mogelijk: configuratie-mismatch — contact beheerder (ref: ${ex.errorId})",
                    ),
                )
            }
            throw ex
        } catch (ex: Exception) {
            // Cause-walking: Mutiny wrapt InterruptedException/TimeoutException; direct
            // instanceof matched niet. isInterrupted (non-destructive) — Thread.interrupted()
            // zou de flag wissen ook in niet-interrupt-branches.
            val wasInterrupted = Thread.currentThread().isInterrupted ||
                ex.hasCauseOf(InterruptedException::class.java)
            val isOuterTimeout = ex is io.smallrye.mutiny.TimeoutException ||
                ex is java.util.concurrent.TimeoutException ||
                ex.hasCauseOf(io.smallrye.mutiny.TimeoutException::class.java) ||
                ex.hasCauseOf(java.util.concurrent.TimeoutException::class.java)

            when {
                wasInterrupted -> {
                    // Interrupt-flag (her)bevestigen voor graceful pod-shutdown; 503 (transient).
                    Thread.currentThread().interrupt()
                    val errorId = UUID.randomUUID()

                    log.warnf(ex, "(errorId=%s) Resolver-await onderbroken voor key=%s", errorId, cacheKey)
                    cleanupLockMetFoutStatus(cacheKey, "resolver-await interrupted", errorId)
                    throw WebApplicationException("Service shutdown tijdens ophalen", 503)
                }
                isOuterTimeout -> {
                    // Bouw exception eerst zodat errorId via log + cleanup + mapper consistent is.
                    val foutException = ProfielServiceFoutException.resolverMislukt(ex)

                    log.errorf(ex, "Resolver await overschreed outer-budget (%ds) (errorId=%s) voor key=%s", outerAwaitSeconds, foutException.errorId, cacheKey)
                    cleanupLockMetFoutStatus(cacheKey, "resolver outer-await timeout", foutException.errorId)
                    throw foutException
                }
                else -> {
                    // Eigen-code-bug: 500 (geen Retry-After) zodat client niet retry'd.
                    val errorId = UUID.randomUUID()

                    log.errorf(ex, "(errorId=%s) Onverwachte fout in resolver-await voor key=%s (cause=%s)", errorId, cacheKey, ex.javaClass.simpleName)
                    cleanupLockMetFoutStatus(cacheKey, "onverwachte resolver-fout: ${ex.javaClass.simpleName}", errorId)
                    throw WebApplicationException("Interne fout tijdens ophalen", 500)
                }
            }
        }

        // De resolver mag alleen magazijn-IDs teruggeven die de factory kent;
        // contract van MagazijnResolver. Een onbekende ID is een bug (drift tussen
        // resolver-config en magazijn-config) en moet hard falen, niet stil leeg-degraderen.
        // Cleanup vóór de throw: zonder dit blijft de lock tot TTL hangen en blokkeert
        // legitieme retries na de drift-fix.
        val allClients = clientFactory.getAllClients()
        val onbekend = resolvedIds - allClients.keys

        if (onbekend.isNotEmpty()) {
            val errorId = UUID.randomUUID()

            log.errorf("(errorId=%s) Drift: resolver leverde onbekende magazijn-IDs %s voor key=%s", errorId, onbekend, cacheKey)
            cleanupLockMetFoutStatus(cacheKey, "drift: resolver leverde onbekende magazijn-IDs", errorId)
            throw IllegalArgumentException("Resolver leverde onbekende magazijn-IDs: $onbekend")
        }

        val clients = allClients.filterKeys { it in resolvedIds }

        // Geen magazijnen → lege resultaten + GEREED-status. Cache overschrijven met
        // lege lijst zodat eventuele stale data uit eerdere sessies niet zichtbaar
        // blijft via GET-endpoints.
        if (clients.isEmpty()) {
            try {
                // Parallel: spaart 1 RTT op hot-path (nieuwe gebruikers zonder opt-ins).
                // Per-Uni .onFailure houdt log-context welke faalde.
                Uni.combine().all()
                    .unis(
                        berichtenCache.store(cacheKey, emptyList())
                            .onFailure().invoke { e -> log.errorf(e, "store(empty) mislukt voor lege magazijn-set, key=%s", cacheKey) },
                        berichtenCache.storeAggregationStatus(cacheKey, AggregationStatus(status = OphalenStatus.GEREED))
                            .onFailure().invoke { e -> log.errorf(e, "storeAggregationStatus(GEREED) mislukt voor lege magazijn-set, key=%s", cacheKey) },
                    )
                    .discardItems()
                    .await().atMost(Duration.ofSeconds(5))
            } catch (ex: Exception) {
                val errorId = UUID.randomUUID()

                log.errorf(ex, "(errorId=%s) Store-fout bij lege magazijn-set voor key=%s", errorId, cacheKey)
                cleanupLockMetFoutStatus(cacheKey, "store-fout bij lege magazijn-set", errorId)
                // Geen ProfielServiceFoutException: de Profiel-call is geslaagd, de cache-write
                // faalde. 500 (cache-fout) routeert via ProblemExceptionMapper en
                // signaleert eigen-infra-issue; 503 met "toestemmingscontrole" zou misleiden.
                throw WebApplicationException("Interne fout bij opslaan resultaten", 500)
            }

            return Multi.createFrom().item(
                MagazijnEvent(
                    event = EventType.OPHALEN_GEREED,
                    totaalBerichten = 0,
                    geslaagd = 0,
                    mislukt = 0,
                    totaalMagazijnen = 0,
                ),
            )
        }

        // synchronizedList(ArrayList) i.p.v. ConcurrentLinkedQueue: Mutiny's merging-stream
        // kan callbacks parallel emitten, dus sync is nodig. Geen lock-free CAS per Node
        // (queue) maar wel goedkoop blocking; payload-size is paar honderd berichten.
        val alleBerichten: MutableList<Bericht> = Collections.synchronizedList(ArrayList())
        val geslaagd = AtomicInteger(0)
        val mislukt = AtomicInteger(0)

        // Happy: alleen statusKey overschrijven; lockKey blijft tot GEREED/FOUT-schrijfactie.
        // Fout: cleanupLockMetFoutStatus schrijft FOUT (geeft lock vrij via interne del).
        try {
            berichtenCache.updateAggregationStatus(
                cacheKey,
                bezigStatus.copy(totaalMagazijnen = clients.size),
            ).await().atMost(Duration.ofSeconds(5))
        } catch (ex: Exception) {
            val errorId = UUID.randomUUID()

            log.errorf(ex, "(errorId=%s) Update aggregatie-status (BEZIG, totaalMagazijnen=%d) mislukt voor key=%s", errorId, clients.size, cacheKey)
            cleanupLockMetFoutStatus(cacheKey, "update-aggregatie-status mislukt", errorId)
            throw WebApplicationException("Cache niet bereikbaar tijdens initialisatie ophaalsessie.", 503)
        }

        val ontvangerString = ontvanger.toCanonicalString()

        val magazijnStreams = clients.map { (magazijnId, client) ->
            val naam = clientFactory.getNaam(magazijnId)

            val gestartEvent = MagazijnEvent(
                event = EventType.MAGAZIJN_BEVRAGING_GESTART,
                magazijnId = magazijnId,
                naam = naam,
            )

            val resultUni = Uni.createFrom().item { client }
                .onItem().transform { c -> c.getBerichten(ontvangerString, null) }
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .ifNoItem().after(Duration.ofSeconds(10)).fail()
                .map<MagazijnResult> { response ->
                    if (response.berichten.size > maxBerichtenPerMagazijn) {
                        // ErrorF nodig voor Loki-alert-routing; SSE-event alleen gaat niet naar logs.
                        log.errorf(
                            "Magazijn %s (%s) overschreed berichten-cap: %d > %d",
                            magazijnId, naam, response.berichten.size, maxBerichtenPerMagazijn,
                        )
                        val foutMessage = "Magazijn leverde meer berichten dan toegestaan (${response.berichten.size} > $maxBerichtenPerMagazijn)"

                        MagazijnResult.Failure(magazijnId, naam, MagazijnResponseOverflow(foutMessage), MagazijnFault.OVERFLOW)
                    } else {
                        MagazijnResult.Success(magazijnId, naam, response.berichten)
                    }
                }
                .onFailure(Exception::class.java).recoverWithItem { error ->
                    val fault = classifyMagazijnFault(error)

                    logMagazijnFault(error, magazijnId, naam, fault)
                    MagazijnResult.Failure(magazijnId, naam, error, fault)
                }

            val resultStream = resultUni.toMulti().map { result ->
                when (result) {
                    is MagazijnResult.Success -> {
                        alleBerichten.addAll(result.berichten)
                        geslaagd.incrementAndGet()
                        MagazijnEvent(
                            event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID,
                            magazijnId = magazijnId,
                            naam = naam,
                            status = MagazijnStatus.OK,
                            aantalBerichten = result.berichten.size,
                        )
                    }
                    is MagazijnResult.Failure -> {
                        mislukt.incrementAndGet()
                        val foutmelding = when (result.fault) {
                            MagazijnFault.TIMEOUT -> "Magazijn reageerde niet binnen de timeout"
                            MagazijnFault.MALFORMED -> "Magazijn leverde onleesbare respons (mogelijk schema-drift, contact beheerder)"
                            MagazijnFault.OVERFLOW -> "Magazijn leverde te veel berichten (responsgrootte overschreden, contact beheerder)"
                            MagazijnFault.HTTP_5XX -> "Magazijn tijdelijk niet bereikbaar"
                            MagazijnFault.HTTP_4XX -> "Magazijn heeft de aanvraag geweigerd (configuratiefout, contact beheerder)"
                            // BIO 14.1.3: generiek bericht aan eindgebruiker; technisch onderscheid alleen in log.
                            MagazijnFault.INTERNAL_BUG, MagazijnFault.NETWORK -> "Magazijn kon niet geraadpleegd worden"
                        }
                        MagazijnEvent(
                            event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID,
                            magazijnId = magazijnId,
                            naam = naam,
                            status = if (result.fault == MagazijnFault.TIMEOUT) MagazijnStatus.TIMEOUT else MagazijnStatus.FOUT,
                            foutmelding = foutmelding,
                        )
                    }
                }
            }

            Multi.createBy().concatenating().streams(
                Multi.createFrom().item(gestartEvent),
                resultStream,
            )
        }

        val allMagazijnEvents = Multi.createBy().merging().streams(magazijnStreams)

        // Aggregatie-pipeline: draait onafhankelijk van de SSE-client door tot voltooiing
        return Multi.createBy().concatenating().streams(
            allMagazijnEvents,
            Uni.createFrom().voidItem()
                .chain { _ ->
                    val berichten = alleBerichten.toList()
                    val status = AggregationStatus(
                        status = OphalenStatus.GEREED,
                        totaalMagazijnen = clients.size,
                        geslaagd = geslaagd.get(),
                        mislukt = mislukt.get(),
                    )

                    // Parallel: store(berichten) en storeAggregationStatus(GEREED) hebben
                    // verschillende keys en geen ordering-afhankelijkheid. Bespaart 1 Redis-RTT
                    // t.o.v. sequentiële .chain (consistent met het lege-magazijn-pad hierboven).
                    Uni.combine().all()
                        .unis(
                            berichtenCache.store(cacheKey, berichten),
                            berichtenCache.storeAggregationStatus(cacheKey, status),
                        )
                        .discardItems()
                        .map { _ ->
                            MagazijnEvent(
                                event = EventType.OPHALEN_GEREED,
                                totaalBerichten = alleBerichten.size,
                                geslaagd = geslaagd.get(),
                                mislukt = mislukt.get(),
                                totaalMagazijnen = clients.size,
                            )
                        }
                }
                .onFailure(Exception::class.java).recoverWithUni { error ->
                    // Eerste fout = store(berichten) of storeAggregationStatus(GEREED) faalde;
                    // cacheKey + counters in log zodat ops kan correleren naar specifieke sessie.
                    log.errorf(
                        error,
                        "Fout bij opslaan in cache na aggregatie (key=%s, berichten=%d, geslaagd=%d, mislukt=%d)",
                        cacheKey, alleBerichten.size, geslaagd.get(), mislukt.get(),
                    )
                    val foutStatus = AggregationStatus(
                        status = OphalenStatus.FOUT,
                        totaalMagazijnen = clients.size,
                        geslaagd = geslaagd.get(),
                        mislukt = mislukt.get(),
                    )
                    berichtenCache.storeAggregationStatus(cacheKey, foutStatus)
                        // FATAL + ALERT-marker: dubbele Redis-fout = cache effectief onbruikbaar
                        // voor deze sessie. Lock blijft tot Redis-TTL hangen. Het prefix wordt
                        // door log-aggregator (Loki/CloudWatch) gefilterd richting alert-routing.
                        .onFailure().invoke { e ->
                            log.fatalf(
                                e,
                                "[ALERT cache_doublefail] Cache-write FAIL/FAIL (key=%s, berichten=%d, geslaagd=%d, mislukt=%d): Redis onbruikbaar voor sessie, lock leunt op TTL",
                                cacheKey, alleBerichten.size, geslaagd.get(), mislukt.get(),
                            )
                        }
                        .onFailure().recoverWithNull()
                        .replaceWith(
                            MagazijnEvent(
                                event = EventType.OPHALEN_FOUT,
                                geslaagd = geslaagd.get(),
                                mislukt = mislukt.get(),
                                totaalMagazijnen = clients.size,
                                foutmelding = "Interne fout bij opslaan van resultaten",
                            )
                        )
                }
                .toMulti()
        )
    }

    /**
     * Best-effort lock-release na fout: schrijft FOUT-status; `storeAggregationStatus`
     * doet intern `del(lockKey)`. Cleanup-failure geslikt — lock leunt dan op TTL.
     *
     * @param errorId caller geeft `ex.errorId` mee (Profiel-pad) zodat de cleanup-log
     *   dezelfde id draagt als `Problem.instance` voor cross-correlatie; non-Profiel
     *   paden krijgen een verse id zodat `[ALERT cache_doublefail]`-pad een anker heeft.
     */
    private fun cleanupLockMetFoutStatus(
        cacheKey: String,
        oorzaak: String,
        errorId: java.util.UUID = java.util.UUID.randomUUID(),
    ) {
        try {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                AggregationStatus(status = OphalenStatus.FOUT),
            ).await().atMost(Duration.ofSeconds(5))

            log.warnf("Lock vrijgegeven na fout (errorId=%s) voor key=%s: %s", errorId, cacheKey, oorzaak)
        } catch (cleanupEx: Exception) {
            // FATAL + ALERT-marker: oorspronkelijke fout PLUS cleanup-fail = lock blijft
            // tot Redis-TTL hangen, ontvanger 60s onbedienbaar. Zelfde marker als het
            // aggregatie-pad (`[ALERT cache_doublefail]`) voor uniforme alert-routing
            // — zonder dit pad zou een Profiel-/resolver-fout + Redis-cleanup-fail
            // onder de radar blijven van de log-aggregator-rules.
            log.fatalf(
                cleanupEx,
                "[ALERT cache_doublefail] Lock-cleanup na fout mislukt (errorId=%s) voor key=%s: %s — lock leunt op TTL",
                errorId,
                cacheKey,
                oorzaak,
            )
        }
    }

    /**
     * Cause-chain walker met cycle-protection (IdentityHashMap). Mutiny wrapt failures,
     * dus directe `instanceof` matched de echte cause niet; circular causes komen
     * zeldzaam voor via proxy-exceptions of test-mocks. Geeft de eerste match terug
     * of `null`.
     */
    private inline fun <reified T : Throwable> Throwable.findCauseOf(): T? {
        val seen = java.util.IdentityHashMap<Throwable, Unit>()
        var cur: Throwable? = this

        while (cur != null && seen.put(cur, Unit) == null) {
            if (cur is T) return cur
            cur = cur.cause
        }

        return null
    }

    /** Convenience: of de cause-chain een [cls]-instance bevat. Gebruikt [findCauseOf] onder. */
    private fun Throwable.hasCauseOf(cls: Class<*>): Boolean {
        val seen = java.util.IdentityHashMap<Throwable, Unit>()
        var cur: Throwable? = this

        while (cur != null && seen.put(cur, Unit) == null) {
            if (cls.isInstance(cur)) return true
            cur = cur.cause
        }

        return false
    }

    private enum class LockAcquireError { JSON_SERIALIZATION, TIMEOUT, IO_FAULT, INTERRUPTED, UNEXPECTED }

    private fun classifyLockAcquireError(ex: Throwable): LockAcquireError = when {
        ex.hasCauseOf(InterruptedException::class.java) -> LockAcquireError.INTERRUPTED
        ex.hasCauseOf(JsonProcessingException::class.java) -> LockAcquireError.JSON_SERIALIZATION
        ex is io.smallrye.mutiny.TimeoutException ||
            ex is java.util.concurrent.TimeoutException ||
            ex.hasCauseOf(io.smallrye.mutiny.TimeoutException::class.java) ||
            ex.hasCauseOf(java.util.concurrent.TimeoutException::class.java) -> LockAcquireError.TIMEOUT
        ex.hasCauseOf(java.io.IOException::class.java) -> LockAcquireError.IO_FAULT
        else -> LockAcquireError.UNEXPECTED
    }

    // Internal voor directe unit-test van de classificatie-tabel (zie service-test).
    internal fun classifyMagazijnFault(error: Throwable): MagazijnFault {
        // Cause-walking voor diep-geneste Mutiny-wraps (CompletionException → ... → JPE),
        // consistent met classifyLockAcquireError. Volgorde: meest-specifiek eerst.
        val webEx = error.findCauseOf<WebApplicationException>()

        return when {
            error.hasCauseOf(MagazijnResponseOverflow::class.java) -> MagazijnFault.OVERFLOW
            error.hasCauseOf(TimeoutException::class.java) -> MagazijnFault.TIMEOUT
            error.hasCauseOf(JsonProcessingException::class.java) -> MagazijnFault.MALFORMED
            error.hasCauseOf(ConnectException::class.java) -> MagazijnFault.NETWORK
            webEx != null -> {
                val status = webEx.response?.status ?: 0

                when {
                    status in 500..599 -> MagazijnFault.HTTP_5XX
                    status >= 400 -> MagazijnFault.HTTP_4XX
                    // WAE zonder status = raw WAE("oeps") zonder Response → eigen-bug.
                    else -> MagazijnFault.INTERNAL_BUG
                }
            }
            error.hasCauseOf(ProcessingException::class.java) -> MagazijnFault.NETWORK
            else -> MagazijnFault.INTERNAL_BUG
        }
    }

    /** Log-level differentieert eigen-bug (errorf) van transient upstream (warnf) voor alert-routing. */
    private fun logMagazijnFault(error: Throwable, magazijnId: String, naam: String?, fault: MagazijnFault) {
        when (fault) {
            MagazijnFault.TIMEOUT ->
                log.warnf(error, "Magazijn %s (%s) timeout", magazijnId, naam)
            MagazijnFault.MALFORMED ->
                log.errorf(error, "Magazijn %s (%s) leverde onleesbare JSON-respons (schema-drift?)", magazijnId, naam)
            // Map-pad logt overflow zelf met counts; warnf hier vangt overflows die
            // onverwacht via dit pad komen (toekomstige refactor) — warn ipv debug
            // omdat prod-INFO-niveau anders silent zou maken.
            MagazijnFault.OVERFLOW ->
                log.warnf(error, "Magazijn %s (%s) overflow via onFailure-pad — onverwacht, map-pad zou moeten loggen", magazijnId, naam)
            MagazijnFault.HTTP_5XX ->
                log.warnf(error, "Magazijn %s (%s) 5xx", magazijnId, naam)
            MagazijnFault.HTTP_4XX ->
                log.errorf(error, "Magazijn %s (%s) 4xx — configuratie/auth-fout", magazijnId, naam)
            MagazijnFault.NETWORK ->
                log.warnf(error, "Magazijn %s (%s) niet bereikbaar (network/processing)", magazijnId, naam)
            MagazijnFault.INTERNAL_BUG ->
                log.errorf(error, "Onverwachte fout bij magazijn %s (%s) (cause=%s)", magazijnId, naam, error.javaClass.simpleName)
        }
    }
}

data class BerichtenPage(
    val berichten: List<Bericht>,
    val page: Int,
    val pageSize: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    init {
        require(page >= 0) { "page mag niet negatief zijn" }
        require(pageSize > 0) { "pageSize moet positief zijn" }
        require(totalElements >= 0) { "totalElements mag niet negatief zijn" }
        require(totalPages >= 0) { "totalPages mag niet negatief zijn" }
    }
}
