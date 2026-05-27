package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

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
        val wasSet = berichtenCache.trySetAggregationStatus(cacheKey, bezigStatus)
            .await().atMost(Duration.ofSeconds(5))

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
        } catch (ex: java.util.concurrent.TimeoutException) {
            // Outer-budget overschreden: resolver kwam niet terug binnen het marge-budget
            // terwijl de inner-timeout zou moeten respecteren. Wijst op een resource-hang,
            // geen upstream-issue — log apart zodat ops dit niet als gewone Profiel-storing leest.
            log.errorf(ex, "Resolver await overschreed outer-budget (%ds) voor key=%s", outerAwaitSeconds, cacheKey)
            cleanupLockMetFoutStatus(cacheKey, "resolver outer-await timeout")
            throw ProfielServiceFoutException.resolverMislukt(ex)
        } catch (ex: ProfielServiceFoutException) {
            cleanupLockMetFoutStatus(cacheKey, "profiel-service-fout: ${ex.javaClass.simpleName}")
            throw ex
        } catch (ex: Exception) {
            cleanupLockMetFoutStatus(cacheKey, "resolver-fout: ${ex.javaClass.simpleName}")
            throw ProfielServiceFoutException.resolverMislukt(ex)
        }

        // De resolver mag alleen magazijn-IDs teruggeven die de factory kent;
        // contract van MagazijnResolver. Een onbekende ID is een bug (drift tussen
        // resolver-config en magazijn-config) en moet hard falen, niet stil leeg-degraderen.
        // Cleanup vóór de throw: zonder dit blijft de lock tot TTL hangen en blokkeert
        // legitieme retries na de drift-fix.
        val allClients = clientFactory.getAllClients()
        val onbekend = resolvedIds - allClients.keys

        if (onbekend.isNotEmpty()) {
            cleanupLockMetFoutStatus(cacheKey, "drift: resolver leverde onbekende magazijn-IDs")
            throw IllegalArgumentException("Resolver leverde onbekende magazijn-IDs: $onbekend")
        }

        val clients = allClients.filterKeys { it in resolvedIds }

        // Geen magazijnen → lege resultaten + GEREED-status. Cache overschrijven met
        // lege lijst zodat eventuele stale data uit eerdere sessies niet zichtbaar
        // blijft via GET-endpoints.
        if (clients.isEmpty()) {
            try {
                Uni.combine().all()
                    .unis(
                        berichtenCache.store(cacheKey, emptyList()),
                        berichtenCache.storeAggregationStatus(cacheKey, AggregationStatus(status = OphalenStatus.GEREED)),
                    )
                    .discardItems()
                    .await().atMost(Duration.ofSeconds(5))
            } catch (ex: Exception) {
                log.errorf(ex, "Fout bij opslaan GEREED-status voor lege magazijn-set, key=%s", cacheKey)
                cleanupLockMetFoutStatus(cacheKey, "store-fout bij lege magazijn-set")
                // Geen ProfielServiceFoutException: de Profiel-call is geslaagd, de cache-write
                // faalde. WebApplicationException(500) routeert via ProblemExceptionMapper en
                // maskeert de message; UncaughtExceptionMapper-vangnet zou een minder specifieke
                // bron-classificatie geven. Aparte 503 met "toestemmingscontrole" zou misleiden.
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

        // Bewaar lock door updateAggregationStatus te gebruiken (geen del(lockKey)).
        // Bij Redis-fout hier leunt de lock op de TTL (60s); volgende ophaal kan dan
        // tijdelijk 409 zien. Acceptabel: cleanup hier zou de status-key alsnog raken
        // (zelfde Redis), dus geen extra robuustheid. De fout-mapper produceert 500.
        try {
            berichtenCache.updateAggregationStatus(
                cacheKey,
                bezigStatus.copy(totaalMagazijnen = clients.size),
            ).await().atMost(Duration.ofSeconds(5))
        } catch (ex: Exception) {
            log.errorf(ex, "Update aggregatie-status (BEZIG, totaalMagazijnen=%d) mislukt voor key=%s", clients.size, cacheKey)
            // Cleanup best-effort; bij faal leunt lock op TTL.
            cleanupLockMetFoutStatus(cacheKey, "update-aggregatie-status mislukt")
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
                    MagazijnResult.Success(magazijnId, naam, response.berichten)
                }
                .onFailure(Exception::class.java).recoverWithItem { error ->
                    when (error) {
                        is TimeoutException ->
                            log.warnf(error, "Magazijn %s (%s) timeout", magazijnId, naam)
                        is ProcessingException ->
                            log.warnf(error, "Magazijn %s (%s) niet bereikbaar (network/processing)", magazijnId, naam)
                        is WebApplicationException -> {
                            val status = error.response?.status ?: 0

                            when {
                                status in 500..599 ->
                                    log.warnf(error, "Magazijn %s (%s) 5xx (%d)", magazijnId, naam, status)
                                status >= 400 ->
                                    log.errorf(error, "Magazijn %s (%s) onverwacht status %d (mogelijk configuratie/auth-fout)", magazijnId, naam, status)
                                else ->
                                    log.warnf(error, "Magazijn %s (%s) WebApplicationException zonder bruikbare status", magazijnId, naam)
                            }
                        }
                        is ConnectException ->
                            log.warnf(error, "Magazijn %s (%s) verbinding geweigerd", magazijnId, naam)
                        else ->
                            log.errorf(error, "Onverwachte fout bij magazijn %s (%s)", magazijnId, naam)
                    }
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
                        val isTimeout = result.error is TimeoutException
                        val httpStatus = (result.error as? WebApplicationException)?.response?.status ?: 0
                        val foutmelding = when {
                            isTimeout -> "Magazijn reageerde niet binnen de timeout"
                            httpStatus in 500..599 -> "Magazijn tijdelijk niet bereikbaar"
                            // 4xx = onze aanvraag wordt geweigerd (auth, contract, ontbrekend record).
                            // Aparte foutmelding zodat eindgebruiker dit niet als transient netwerkfout
                            // verwart en operations weet dat dit een configuratie-/integratiefout is.
                            httpStatus in 400..499 -> "Magazijn heeft de aanvraag geweigerd (configuratiefout, contact beheerder)"
                            else -> "Magazijn kon niet geraadpleegd worden"
                        }
                        MagazijnEvent(
                            event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID,
                            magazijnId = magazijnId,
                            naam = naam,
                            status = if (isTimeout) MagazijnStatus.TIMEOUT else MagazijnStatus.FOUT,
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
    private fun cleanupLockMetFoutStatus(cacheKey: String, oorzaak: String) {
        try {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                AggregationStatus(status = OphalenStatus.FOUT),
            ).await().atMost(Duration.ofSeconds(5))

            log.warnf("Lock vrijgegeven na fout voor key=%s: %s", cacheKey, oorzaak)
        } catch (cleanupEx: Exception) {
            log.errorf(cleanupEx, "Lock-cleanup na fout mislukt voor key=%s: %s", cacheKey, oorzaak)
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
