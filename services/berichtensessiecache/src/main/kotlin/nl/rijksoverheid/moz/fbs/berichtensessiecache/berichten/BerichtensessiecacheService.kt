package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClientFactory
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResolver
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResult
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import org.jboss.logging.Logger
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@ApplicationScoped
class BerichtensessiecacheService(
    private val berichtenCache: BerichtenCache,
    private val clientFactory: MagazijnClientFactory,
    private val resolver: MagazijnResolver,
) {
    private val log = Logger.getLogger(BerichtensessiecacheService::class.java)

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
     * Orkestreert het ophalen van berichten uit alle magazijnen, slaat ze op in de cache,
     * en retourneert een SSE-compatible Multi met statusevents per magazijn.
     */
    fun ophalenBerichten(ontvanger: Identificatienummer): Multi<MagazijnEvent> {
        val cacheKey = BerichtenCache.cacheKey(ontvanger)

        val bezigStatus = AggregationStatus(
            status = OphalenStatus.BEZIG,
            totaalMagazijnen = 0,
        )

        // Atomaire lock: voorkom concurrent ophalen voor dezelfde ontvanger (SETNX).
        // N.B. Blokkerende await() is hier bewust: het aanroepende endpoint (BerichtenOphalenResource)
        // is @Blocking gemarkeerd. De lock-check moet synchroon afgerond zijn voordat de
        // Multi-stream gestart wordt, omdat de 409-response anders niet meer mogelijk is.
        val wasSet = berichtenCache.trySetAggregationStatus(cacheKey, bezigStatus)
            .await().atMost(Duration.ofSeconds(5))

        if (!wasSet) {
            throw WebApplicationException(
                "Berichten worden momenteel al opgehaald voor deze ontvanger. Wacht tot het ophalen is afgerond.",
                409,
            )
        }

        val resolvedIds = try {
            resolver.resolve(ontvanger).await().atMost(Duration.ofSeconds(20))
        } catch (ex: Exception) {
            cleanupLockMetFoutStatus(cacheKey, "resolver-fout: ${ex.javaClass.simpleName}")

            if (ex is ProfielServiceFoutException) throw ex

            throw ProfielServiceFoutException(
                "Resolver-aanroep mislukt (${ex.javaClass.simpleName})",
                ex,
            )
        }

        val clients = clientFactory.getAllClients().filterKeys { it in resolvedIds }

        // Geen magazijnen → lege resultaten + GEREED-status. Cache overschrijven met
        // lege lijst zodat eventuele stale data uit eerdere sessies niet zichtbaar
        // blijft via GET-endpoints.
        if (clients.isEmpty()) {
            try {
                berichtenCache.store(cacheKey, emptyList()).await().atMost(Duration.ofSeconds(5))
                berichtenCache.storeAggregationStatus(
                    cacheKey,
                    AggregationStatus(status = OphalenStatus.GEREED, totaalMagazijnen = 0, geslaagd = 0, mislukt = 0),
                ).await().atMost(Duration.ofSeconds(5))
            } catch (ex: Exception) {
                log.errorf(ex, "Fout bij opslaan GEREED-status voor lege magazijn-set, key=%s", cacheKey)
                cleanupLockMetFoutStatus(cacheKey, "store-fout bij lege magazijn-set")
                throw ProfielServiceFoutException("Interne fout bij opslaan resultaten", ex)
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

        val alleBerichten = ConcurrentLinkedQueue<Bericht>()
        val geslaagd = AtomicInteger(0)
        val mislukt = AtomicInteger(0)

        // Bewaar lock door updateAggregationStatus te gebruiken (geen del(lockKey)).
        berichtenCache.updateAggregationStatus(
            cacheKey,
            bezigStatus.copy(totaalMagazijnen = clients.size),
        ).await().atMost(Duration.ofSeconds(5))

        val magazijnStreams = clients.map { (magazijnId, client) ->
            val naam = clientFactory.getNaam(magazijnId)

            val gestartEvent = MagazijnEvent(
                event = EventType.MAGAZIJN_BEVRAGING_GESTART,
                magazijnId = magazijnId,
                naam = naam,
            )

            val resultUni = Uni.createFrom().item { client }
                .onItem().transform { c -> c.getBerichten(ontvanger.toCanonicalString(), null) }
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .ifNoItem().after(Duration.ofSeconds(10)).fail()
                .map<MagazijnResult> { response ->
                    MagazijnResult.Success(magazijnId, naam, response.berichten)
                }
                .onFailure(Exception::class.java).recoverWithItem { error ->
                    when (error) {
                        is io.smallrye.mutiny.TimeoutException ->
                            log.warnf(error, "Magazijn %s (%s) timeout", magazijnId, naam)
                        is jakarta.ws.rs.ProcessingException ->
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
                        is java.net.ConnectException ->
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
                        val isTimeout = result.error is io.smallrye.mutiny.TimeoutException
                        val foutmelding = when {
                            isTimeout -> "Magazijn reageerde niet binnen de timeout"
                            result.error is WebApplicationException &&
                                (result.error.response?.status ?: 0) in 500..599 ->
                                "Magazijn tijdelijk niet bereikbaar"
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
                    berichtenCache.store(cacheKey, berichten)
                        .chain { _ ->
                            val status = AggregationStatus(
                                status = OphalenStatus.GEREED,
                                totaalMagazijnen = clients.size,
                                geslaagd = geslaagd.get(),
                                mislukt = mislukt.get(),
                            )
                            berichtenCache.storeAggregationStatus(cacheKey, status)
                        }
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
                    log.errorf(error, "Fout bij opslaan in cache na aggregatie")
                    val foutStatus = AggregationStatus(
                        status = OphalenStatus.FOUT,
                        totaalMagazijnen = clients.size,
                        geslaagd = geslaagd.get(),
                        mislukt = mislukt.get(),
                    )
                    berichtenCache.storeAggregationStatus(cacheKey, foutStatus)
                        .onFailure().invoke { e -> log.errorf(e, "Best-effort FOUT status opslaan ook mislukt") }
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
     * Timeout op 5s zodat een hangende Redis de thread niet eeuwig blokkeert.
     */
    private fun cleanupLockMetFoutStatus(cacheKey: String, foutmelding: String) {
        try {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                AggregationStatus(status = OphalenStatus.FOUT, totaalMagazijnen = 0),
            ).await().atMost(Duration.ofSeconds(5))
        } catch (cleanupEx: Exception) {
            log.errorf(cleanupEx, "Lock-cleanup na fout mislukt voor key=%s: %s", cacheKey, foutmelding)
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
