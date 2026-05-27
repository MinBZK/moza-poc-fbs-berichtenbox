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
    // Availability-cap per magazijn-response: voorkomt dat een rogue/buggy magazijn
    // de heap volpompt. Default 200 = ~2 ordes boven realistisch werkpunt (CLAUDE.md:
    // "paar berichten, paar KB elk"), genoeg marge voor bulk-historical scenarios
    // zonder dat de cap dead-weight wordt. Werkt als belt-and-suspenders naast
    // `quarkus.http.limits.max-body-size` (byte-niveau). Override via property bij
    // gemeten productie-bulk; niet stilzwijgend verhogen zonder load-evidence.
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
            // Cause-walking ipv directe instanceof: Mutiny's `await().atMost()` wrapt
            // upstream-failures in CompletionException-achtige containers, dus een directe
            // catch op JsonProcessingException matched in productie nooit. We klimmen de
            // cause-chain in om de echte fout-categorie te bepalen.
            val errorId = UUID.randomUUID()
            val classification = classifyLockAcquireError(ex)

            when (classification) {
                LockAcquireError.JSON_SERIALIZATION -> {
                    log.errorf(ex, "(errorId=%s) Lock-acquire mislukt door JSON-serialisatie-fout voor key=%s (eigen-code-bug, geen Redis-issue)", errorId, cacheKey)
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
                    log.errorf(ex, "(errorId=%s) Lock-acquire onverwachte fout voor key=%s (cause=%s) — eigen-code-bug?", errorId, cacheKey, ex.javaClass.simpleName)
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
            // Geef ex.errorId mee zodat de cleanup-log dezelfde id draagt als de
            // mapper-respons (Problem.instance = urn:uuid:<errorId>); support kan
            // dan vanuit een client-side 503 alle service-side regels vinden.
            cleanupLockMetFoutStatus(cacheKey, "profiel-service-fout: ${ex.categorie.name}", ex.errorId)
            throw ex
        } catch (ex: Exception) {
            // Cause-walking voor InterruptedException + TimeoutException: Mutiny's
            // `await().atMost()` gooit InterruptedException niet direct maar wrapt 'm
            // in een RuntimeException; een directe catch op InterruptedException werkt
            // dus niet. Cause-walking + `Thread.interrupted()`-check vangt beide
            // routes (direct én gewrapt) en herstelt de interrupt-flag.
            val wasInterrupted = Thread.interrupted() || ex.hasCauseOf(InterruptedException::class.java)
            val isOuterTimeout = ex is io.smallrye.mutiny.TimeoutException ||
                ex is java.util.concurrent.TimeoutException ||
                ex.hasCauseOf(io.smallrye.mutiny.TimeoutException::class.java) ||
                ex.hasCauseOf(java.util.concurrent.TimeoutException::class.java)

            when {
                wasInterrupted -> {
                    // Herstel interrupt-flag zodat bovenliggende thread-pool cancellation
                    // ziet (graceful pod-shutdown). 503 (transient) i.p.v. 500 — shutdown
                    // is geen eigen-bug.
                    Thread.currentThread().interrupt()
                    val errorId = UUID.randomUUID()

                    log.warnf(ex, "(errorId=%s) Resolver-await onderbroken (pod-shutdown of test-cancel) voor key=%s", errorId, cacheKey)
                    cleanupLockMetFoutStatus(cacheKey, "resolver-await interrupted", errorId)
                    throw WebApplicationException("Service shutdown tijdens ophalen", 503)
                }
                isOuterTimeout -> {
                    // Bouw eerst de exception (genereert errorId) zodat we 'm aan zowel de
                    // root-cause-log als de cleanup-log kunnen meegeven; mapper hergebruikt
                    // dezelfde id voor Problem.instance.
                    val foutException = ProfielServiceFoutException.resolverMislukt(ex)

                    log.errorf(ex, "Resolver await overschreed outer-budget (%ds) (errorId=%s) voor key=%s", outerAwaitSeconds, foutException.errorId, cacheKey)
                    cleanupLockMetFoutStatus(cacheKey, "resolver outer-await timeout", foutException.errorId)
                    throw foutException
                }
                else -> {
                    // Eigen-code-bug of pipeline-fout: 500 (geen Retry-After) zodat client
                    // niet retry'd op niet-transient fout en ops niet een Profiel-storing
                    // gaat zoeken die er niet is.
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
                // Parallel via Uni.combine().all(): beide writes hebben verschillende keys
                // en geen ordering-afhankelijkheid. Per-Uni .onFailure().invoke geeft nog
                // steeds aparte log-context bij failure (welke van de twee mislukte) zonder
                // de extra RTT die sequentieel .chain kost — dit pad is het meest voorkomende
                // request-pattern voor nieuwe gebruikers zonder opt-ins.
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

        // updateAggregationStatus(BEZIG, totaalMagazijnen) overschrijft alleen de statusKey
        // (de lockKey blijft staan in het happy path → concurrent ophalen krijgt 409 tot deze
        // sessie GEREED/FOUT schrijft, wat de lock semantisch vrijgeeft via storeAggregationStatus).
        // Bij Redis-fout op deze update gaat cleanupLockMetFoutStatus de lock alsnog vrijgeven
        // door FOUT te schrijven (storeAggregationStatus doet intern del(lockKey)); faalt die
        // cleanup ook, dan leunt de vrijgave op de Redis-TTL (60s) — `[ALERT cache_doublefail]`-pad.
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
                        // ErrorF-log met counts zodat ops vanuit Loki/CloudWatch direct kan
                        // correleren naar het magazijn dat de cap raakte (rogue / contract-bug).
                        // Zonder dit log zou alleen het SSE-event-foutmelding ops bereiken — niet
                        // alert-baar via log-routing.
                        log.errorf(
                            "Magazijn %s (%s) overschreed berichten-cap: %d > %d (rogue magazijn of contract-bug — response gediskwalificeerd)",
                            magazijnId, naam, response.berichten.size, maxBerichtenPerMagazijn,
                        )
                        val foutMessage = "Magazijn leverde meer berichten dan toegestaan (${response.berichten.size} > $maxBerichtenPerMagazijn)"

                        MagazijnResult.Failure(magazijnId, naam, MagazijnResponseOverflow(foutMessage))
                    } else {
                        MagazijnResult.Success(magazijnId, naam, response.berichten)
                    }
                }
                .onFailure(Exception::class.java).recoverWithItem { error ->
                    logMagazijnFault(error, magazijnId, naam, classifyMagazijnFault(error))
                    MagazijnResult.Failure(magazijnId, naam, error)
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
                        // Eén classificatie-keten (`classifyMagazijnFault`) ipv duplicate
                        // instanceof-cascades voor log én foutmelding. Nieuwe error-categorie
                        // toevoegen = één plek aanpassen (het MagazijnFault-enum + de when's).
                        val fault = classifyMagazijnFault(result.error)
                        val foutmelding = when (fault) {
                            MagazijnFault.TIMEOUT -> "Magazijn reageerde niet binnen de timeout"
                            MagazijnFault.MALFORMED -> "Magazijn leverde onleesbare respons (mogelijk schema-drift, contact beheerder)"
                            MagazijnFault.OVERFLOW -> "Magazijn leverde te veel berichten (responsgrootte overschreden, contact beheerder)"
                            MagazijnFault.HTTP_5XX -> "Magazijn tijdelijk niet bereikbaar"
                            // 4xx = onze aanvraag wordt geweigerd (auth, contract, ontbrekend record).
                            // Aparte foutmelding zodat eindgebruiker dit niet als transient netwerkfout
                            // verwart en operations weet dat dit een configuratie-/integratiefout is.
                            MagazijnFault.HTTP_4XX -> "Magazijn heeft de aanvraag geweigerd (configuratiefout, contact beheerder)"
                            // Interne-bug-paden: GENERIEKE eindgebruiker-tekst (geen "interne fout"-
                            // signaal dat fuzzers/recon kan helpen). Technisch onderscheid blijft
                            // alleen in de applicatielog. BIO 14.1.3 compliant.
                            MagazijnFault.INTERNAL_BUG -> "Magazijn kon niet geraadpleegd worden"
                            MagazijnFault.NETWORK -> "Magazijn kon niet geraadpleegd worden"
                        }
                        MagazijnEvent(
                            event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID,
                            magazijnId = magazijnId,
                            naam = naam,
                            status = if (fault == MagazijnFault.TIMEOUT) MagazijnStatus.TIMEOUT else MagazijnStatus.FOUT,
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
     * Best-effort: zet FOUT-status om de lock vrij te geven na een niet-herstelbare fout.
     * `storeAggregationStatus` doet intern `del(lockKey)`, dus deze ene call ruimt zowel
     * status als lock op. Timeout op 5s zodat een hangende Redis de thread niet eeuwig
     * blokkeert; falen wordt geslikt (ook al blijft de lock dan tot Redis-TTL hangen,
     * dat is acceptabel — beter dan een tweede exception over de eerste heen).
     *
     * @param oorzaak korte oorzaak-omschrijving voor de log; zichtbaar zowel bij geslaagde
     *   cleanup (warn met context welke ophaal-fout de lock vrijgaf) als bij cleanup-fail.
     */
    /**
     * @param errorId optionele correlatie-id. Wanneer de caller een [ProfielServiceFoutException]
     *   afhandelt geeft hij `ex.errorId` mee zodat support een client-side
     *   `urn:uuid:<errorId>` (uit `Problem.instance`) direct kan correleren naar deze
     *   cleanup-logregel én naar de root-cause-logregel hieronder. Voor non-Profiel
     *   foutpaden (Redis-blip, onverwachte Exception) genereert de helper zelf een
     *   nieuwe id zodat ook die fouten een correlatie-anker krijgen in `[ALERT
     *   cache_doublefail]`-routing.
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
     * Loopt de cause-chain af en checkt of een gegeven exception-type ergens voorkomt.
     * Nodig omdat Mutiny's blocking-await failures wrapt — een directe `instanceof`-check
     * matched de onderliggende cause niet.
     */
    private fun Throwable.hasCauseOf(cls: Class<*>): Boolean =
        generateSequence(this as Throwable?) { it.cause }.any { cls.isInstance(it) }

    private enum class LockAcquireError { JSON_SERIALIZATION, TIMEOUT, IO_FAULT, UNEXPECTED }

    private fun classifyLockAcquireError(ex: Throwable): LockAcquireError = when {
        ex.hasCauseOf(JsonProcessingException::class.java) -> LockAcquireError.JSON_SERIALIZATION
        ex is io.smallrye.mutiny.TimeoutException ||
            ex is java.util.concurrent.TimeoutException ||
            ex.hasCauseOf(io.smallrye.mutiny.TimeoutException::class.java) ||
            ex.hasCauseOf(java.util.concurrent.TimeoutException::class.java) -> LockAcquireError.TIMEOUT
        ex.hasCauseOf(java.io.IOException::class.java) -> LockAcquireError.IO_FAULT
        else -> LockAcquireError.UNEXPECTED
    }

    private enum class MagazijnFault { TIMEOUT, MALFORMED, OVERFLOW, HTTP_5XX, HTTP_4XX, NETWORK, INTERNAL_BUG }

    private fun classifyMagazijnFault(error: Throwable): MagazijnFault = when {
        error is TimeoutException -> MagazijnFault.TIMEOUT
        error is MagazijnResponseOverflow -> MagazijnFault.OVERFLOW
        error is JsonProcessingException ||
            (error is ProcessingException && error.cause is JsonProcessingException) -> MagazijnFault.MALFORMED
        error is ConnectException -> MagazijnFault.NETWORK
        error is ProcessingException -> MagazijnFault.NETWORK
        error is WebApplicationException -> {
            val status = error.response?.status ?: 0
            when {
                status in 500..599 -> MagazijnFault.HTTP_5XX
                status >= 400 -> MagazijnFault.HTTP_4XX
                // WAE zonder bruikbare status = eigen-code-bug (client gooit raw
                // WAE("oeps") zonder Response).
                else -> MagazijnFault.INTERNAL_BUG
            }
        }
        // Onverwachte Exception (NPE/IllegalState/ClassCast uit gegenereerde client of mapping).
        else -> MagazijnFault.INTERNAL_BUG
    }

    private fun logMagazijnFault(error: Throwable, magazijnId: String, naam: String?, fault: MagazijnFault) {
        // Aparte logf-paden zodat ops in Loki/CloudWatch op log-niveau (errorf vs warnf)
        // kan filteren wat een eigen-code-bug is (errorf) versus transient upstream (warnf).
        when (fault) {
            MagazijnFault.TIMEOUT ->
                log.warnf(error, "Magazijn %s (%s) timeout", magazijnId, naam)
            MagazijnFault.MALFORMED ->
                // ErrorF: deterministisch contract-issue, niet transient — moet zichtbaar in alerts.
                log.errorf(error, "Magazijn %s (%s) leverde onleesbare JSON-respons (schema-drift?)", magazijnId, naam)
            MagazijnFault.OVERFLOW ->
                // Al gelogd op het map-pad waar de overflow gedetecteerd werd; geen dubbele log.
                Unit
            MagazijnFault.HTTP_5XX ->
                log.warnf(error, "Magazijn %s (%s) 5xx", magazijnId, naam)
            MagazijnFault.HTTP_4XX ->
                log.errorf(error, "Magazijn %s (%s) 4xx — configuratie/auth-fout", magazijnId, naam)
            MagazijnFault.NETWORK ->
                log.warnf(error, "Magazijn %s (%s) niet bereikbaar (network/processing)", magazijnId, naam)
            MagazijnFault.INTERNAL_BUG ->
                // ErrorF + generieke eindgebruiker-foutmelding: ops onderscheid eigen-bug vs upstream;
                // attacker krijgt geen recon-signal door de generieke foutmelding (zie classifier-mapping).
                log.errorf(error, "Onverwachte fout bij magazijn %s (%s) — interne bug? (cause=%s)", magazijnId, naam, error.javaClass.simpleName)
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
