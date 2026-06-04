package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClient
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClientFactory
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResolver
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResult
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@ApplicationScoped
class BerichtensessiecacheService(
    private val berichtenCache: BerichtenCache,
    private val clientFactory: MagazijnClientFactory,
    private val berichtValidator: BerichtValidator,
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
        // aanslaat; anders verliest de caller de juiste foutclassificatie (Profiel traag
        // vs resolver hangt).
        require(outerAwaitSeconds > innerTimeoutSeconds) {
            "profiel.resolver.outer-await-seconds ($outerAwaitSeconds) moet groter zijn dan " +
                "profiel.resolver.inner-timeout-seconds ($innerTimeoutSeconds)"
        }
    }

    fun getBerichten(page: Int, pageSize: Int, ontvanger: Identificatienummer, afzender: String?): Uni<BerichtenPage> {
        log.debugf("Ophalen berichten uit cache: page=%d, pageSize=%d", page, pageSize)
        val key = BerichtenCache.cacheKey(ontvanger)

        // Cache-miss (null) en "opgehaald, 0 berichten" collapsen bewust naar dezelfde lege
        // pagina; het onderscheid loopt via getAggregationStatus.
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
        // q is user-input zonder CRLF-filter op spec-niveau; loggen van q.length voorkomt
        // log-injectie via newline-payloads.
        log.debugf("Zoeken berichten via RediSearch: q.length=%d, page=%d, pageSize=%d", q.length, page, pageSize)

        return berichtenCache.search(ontvanger, q, page, pageSize, afzender)
    }

    fun updateBerichtMetadata(berichtId: UUID, ontvanger: Identificatienummer, status: String?, map: String?): Uni<Bericht?> {
        log.debugf("Bijwerken bericht: berichtId=%s, status=%s, map=%s", berichtId, status, map)

        return berichtenCache.updateBerichtMetadata(berichtId, ontvanger, status, map)
    }

    fun addBericht(bericht: Bericht, ontvanger: Identificatienummer): Uni<Bericht> {
        log.debugf("Toevoegen bericht aan cache: berichtId=%s", bericht.berichtId)
        val gevalideerd = berichtValidator.valideer(bericht)

        return berichtenCache.addBericht(gevalideerd, ontvanger).replaceWith(gevalideerd)
    }

    fun verwijderBericht(berichtId: UUID, ontvanger: Identificatienummer): Uni<Void> {
        log.debugf("Verwijderen bericht uit cache: berichtId=%s", berichtId)

        return berichtenCache.delete(berichtId, ontvanger)
    }

    /**
     * Orkestreert het ophalen van berichten uit de magazijnen die de [MagazijnResolver]
     * voor deze ontvanger relevant acht (voorkeur-gestuurd voor BSN/RSIN/KVK, alle voor
     * OIN-B2B), slaat ze op in de cache, en retourneert een SSE-compatible Multi met
     * statusevents per magazijn.
     */
    fun haalBerichtenOp(ontvanger: Identificatienummer): Multi<MagazijnEvent> {
        val cacheKey = BerichtenCache.cacheKey(ontvanger)

        // BEZIG-lock zetten vóór de resolver-call. Bij collision (al een lopende ophaal-flow
        // voor deze ontvanger) gooien we synchroon 409 vóór de Multi-stream start — anders is
        // een 409 niet meer mogelijk omdat de response al committed is.
        // totaalMagazijnen=0 in BEZIG-status: de resolver bepaalt het echte aantal pas hierna;
        // het OPHALEN_GEREED-event reports het correcte totaal.
        setBezigStatusOfFaalMet409(cacheKey)

        // Resolver bepaalt welke magazijnen relevant zijn. Profiel-fout = fail-closed:
        // lock vrijgeven; geen "alle magazijnen"-fallback (een Profiel-fout is geen
        // impliciete toestemming).
        val resolvedIds = try {
            resolver.resolve(ontvanger).await().atMost(Duration.ofSeconds(outerAwaitSeconds))
        } catch (ex: ProfielServiceFoutException) {
            releaseLockNaFout(cacheKey)

            // CONFIG_DRIFT = eigen-config-fout, niet Profiel-storing: emit een zichtbare
            // OPHALEN_FOUT zodat de client wéét "geen ophaling mogelijk" i.p.v. een 503 dat
            // tot zinloze retries leidt op een permanente fout.
            if (ex.categorie == ProfielServiceFoutException.Categorie.CONFIG_DRIFT) {
                val ref = ex.errorId.toString()

                return Multi.createFrom().item(
                    MagazijnEvent(
                        event = EventType.OPHALEN_FOUT,
                        totaalMagazijnen = 0,
                        foutmelding = "Geen ophaling mogelijk: configuratie-mismatch — contact beheerder (ref: $ref)",
                        referentie = ref,
                    ),
                )
            }

            throw ex
        } catch (ex: Exception) {
            releaseLockNaFout(cacheKey)
            throw ProfielServiceFoutException.resolverMislukt(ex)
        }

        // De resolver mag alleen magazijn-IDs teruggeven die de factory kent. Een onbekende
        // ID is config-drift tussen resolver- en magazijn-config en moet hard falen, niet
        // stil minder magazijnen bevragen. Lock vrijgeven vóór de throw zodat retries na de
        // fix niet tot TTL blokkeren.
        val allClients = clientFactory.getAllClients()
        val onbekend = resolvedIds - allClients.keys

        if (onbekend.isNotEmpty()) {
            log.errorf("Drift: resolver leverde onbekende magazijn-IDs %s voor key=%s", onbekend, cacheKey)
            releaseLockNaFout(cacheKey)

            throw IllegalArgumentException("Resolver leverde onbekende magazijn-IDs: $onbekend")
        }

        val clients = allClients.filterKeys { it in resolvedIds }

        // Geen magazijnen → lege resultaten + GEREED.
        if (clients.isEmpty()) {
            return legeResultaten(cacheKey)
        }

        val acc = OphaalAccumulator(clients.size)
        val perMagazijnStreams = clients.map { (id, client) -> magazijnStream(id, client, ontvanger, acc) }
        val voltooiing = voltooiingsPipeline(cacheKey, acc).toMulti()

        return Multi.createBy().concatenating().streams(
            Multi.createBy().merging().streams(perMagazijnStreams),
            voltooiing,
        )
    }

    /**
     * Zet via SETNX de BEZIG-status; bij collision (al een lopende ophaal-flow voor deze
     * ontvanger) gooien we synchroon 409 vóór de Multi-stream start.
     */
    private fun setBezigStatusOfFaalMet409(cacheKey: String) {
        val bezigStatus = AggregationStatus(
            status = OphalenStatus.BEZIG,
            totaalMagazijnen = 0,
        )

        // Blokkerende await() is hier bewust: het aanroepende endpoint is @Blocking.
        val wasSet = berichtenCache.trySetAggregationStatus(cacheKey, bezigStatus)
            .await().atMost(Duration.ofSeconds(5))

        if (!wasSet) {
            throw WebApplicationException(
                "Berichten worden momenteel al opgehaald voor deze ontvanger. Wacht tot het ophalen is afgerond.",
                409,
            )
        }
    }

    /**
     * Lege-magazijn-pad: cache overschrijven met lege lijst zodat stale data uit eerdere
     * sessies niet zichtbaar blijft via GET-endpoints, gevolgd door één OPHALEN_GEREED-event.
     */
    private fun legeResultaten(cacheKey: String): Multi<MagazijnEvent> =
        berichtenCache.store(cacheKey, emptyList())
            .chain { _ -> berichtenCache.storeAggregationStatus(cacheKey, AggregationStatus(status = OphalenStatus.GEREED)) }
            .map<MagazijnEvent> { _ ->
                MagazijnEvent(
                    event = EventType.OPHALEN_GEREED,
                    totaalBerichten = 0,
                    geslaagd = 0,
                    mislukt = 0,
                    totaalMagazijnen = 0,
                )
            }
            .toMulti()

    /**
     * Best-effort lock-release na een fout vóór de SSE-stream start: schrijft FOUT-status
     * (`storeAggregationStatus` doet intern `del(lockKey)`). Cleanup-failure wordt geslikt
     * — de lock leunt dan op de Redis-TTL.
     */
    private fun releaseLockNaFout(cacheKey: String) {
        try {
            berichtenCache.storeAggregationStatus(cacheKey, AggregationStatus(status = OphalenStatus.FOUT))
                .await().atMost(Duration.ofSeconds(5))
        } catch (cleanupEx: Exception) {
            log.errorf(cleanupEx, "Lock-cleanup na fout mislukt voor key=%s — lock leunt op TTL", cacheKey)
        }
    }

    /**
     * Levert per magazijn een Multi met een GESTART-event gevolgd door een VOLTOOID-event.
     * Resultaten landen in [acc] zodat de [voltooiingsPipeline] ze in de cache kan opslaan.
     */
    private fun magazijnStream(
        magazijnId: String,
        client: MagazijnClient,
        ontvanger: Identificatienummer,
        acc: OphaalAccumulator,
    ): Multi<MagazijnEvent> {
        val naam = clientFactory.getNaam(magazijnId)
        val ontvangerString = ontvanger.toCanonicalString()

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
                // Filter defensieve grenzen (BerichtLimieten) op binnenkomende magazijn-data.
                // Eén invalid bericht mag de batch niet killen — drop het stuk en log warn.
                val berichten = response.berichten
                    .map { it.toBericht(magazijnId) }
                    .mapNotNull { berichtValidator.valideerOrLogAndDrop(it) }
                MagazijnResult.Success(magazijnId, naam, berichten)
            }
            .onFailure(Exception::class.java).recoverWithItem { error ->
                when (error) {
                    is jakarta.ws.rs.ProcessingException,
                    is WebApplicationException,
                    is io.smallrye.mutiny.TimeoutException,
                    is java.net.ConnectException ->
                        log.warnf(error, "Magazijn %s (%s) niet bereikbaar", magazijnId, naam)
                    else ->
                        log.errorf(error, "Onverwachte fout bij magazijn %s (%s)", magazijnId, naam)
                }
                MagazijnResult.Failure(magazijnId, naam, error)
            }

        val resultStream = resultUni.toMulti().map { result ->
            when (result) {
                is MagazijnResult.Success -> {
                    acc.berichten.addAll(result.berichten)
                    acc.geslaagd.incrementAndGet()
                    MagazijnEvent(
                        event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID,
                        magazijnId = magazijnId,
                        naam = naam,
                        status = MagazijnStatus.OK,
                        aantalBerichten = result.berichten.size,
                    )
                }
                is MagazijnResult.Failure -> {
                    acc.mislukt.incrementAndGet()
                    val isTimeout = result.error is io.smallrye.mutiny.TimeoutException
                    MagazijnEvent(
                        event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID,
                        magazijnId = magazijnId,
                        naam = naam,
                        status = if (isTimeout) MagazijnStatus.TIMEOUT else MagazijnStatus.FOUT,
                        foutmelding = if (isTimeout) "Magazijn reageerde niet binnen de timeout" else "Magazijn tijdelijk niet bereikbaar",
                    )
                }
            }
        }

        return Multi.createBy().concatenating().streams(
            Multi.createFrom().item(gestartEvent),
            resultStream,
        )
    }

    /**
     * Schrijft de geaccumuleerde berichten naar de cache en zet de aggregatie-status op GEREED
     * (ook bij partial failure — FOUT zou de geslaagde berichten verbergen). Bij een cache-fout
     * landen we op OPHALEN_FOUT met best-effort FOUT-status-write zodat de volgende request
     * niet onterecht op GEREED-state vertrouwt.
     */
    private fun voltooiingsPipeline(cacheKey: String, acc: OphaalAccumulator): Uni<MagazijnEvent> {
        return Uni.createFrom().voidItem()
            .chain { _ ->
                val berichten = acc.berichten.toList()
                berichtenCache.store(cacheKey, berichten)
                    .chain { _ ->
                        // GEREED = "aggregatie voltooid", NIET "alle magazijnen geslaagd".
                        // Het partial-failure-signaal reist mee via de SSE-events
                        // (per-magazijn FOUT/TIMEOUT + het OPHALEN_GEREED-event met
                        // geslaagd/mislukt). De latere GET /berichten is daarmee
                        // best-effort: status FOUT is voorbehouden aan een totale
                        // mislukking (zie OphalenStatus.FOUT in requireGereedStatus).
                        val status = AggregationStatus(
                            status = OphalenStatus.GEREED,
                            totaalMagazijnen = acc.totaalMagazijnen,
                            geslaagd = acc.geslaagd.get(),
                            mislukt = acc.mislukt.get(),
                        )

                        berichtenCache.storeAggregationStatus(cacheKey, status)
                    }
                    .map { _ ->
                        MagazijnEvent(
                            event = EventType.OPHALEN_GEREED,
                            totaalBerichten = acc.berichten.size,
                            geslaagd = acc.geslaagd.get(),
                            mislukt = acc.mislukt.get(),
                            totaalMagazijnen = acc.totaalMagazijnen,
                        )
                    }
            }
            .onFailure(Exception::class.java).recoverWithUni { error ->
                log.errorf(error, "Fout bij opslaan in cache na aggregatie (key=%s)", cacheKey)
                val foutStatus = AggregationStatus(
                    status = OphalenStatus.FOUT,
                    totaalMagazijnen = acc.totaalMagazijnen,
                    geslaagd = acc.geslaagd.get(),
                    mislukt = acc.mislukt.get(),
                )

                berichtenCache.storeAggregationStatus(cacheKey, foutStatus)
                    .onFailure().invoke { e -> log.errorf(e, "Best-effort FOUT-status opslaan ook mislukt") }
                    .onFailure().recoverWithNull()
                    .replaceWith(
                        MagazijnEvent(
                            event = EventType.OPHALEN_FOUT,
                            geslaagd = acc.geslaagd.get(),
                            mislukt = acc.mislukt.get(),
                            totaalMagazijnen = acc.totaalMagazijnen,
                            foutmelding = "Interne fout bij opslaan van resultaten",
                        )
                    )
            }
    }

    /**
     * Accumulator voor de parallel-draaiende magazijn-streams. Wrapt de gedeelde mutable state
     * (queue + counters) zodat [magazijnStream] en [voltooiingsPipeline] één argument delen.
     */
    private class OphaalAccumulator(val totaalMagazijnen: Int) {
        val berichten: ConcurrentLinkedQueue<Bericht> = ConcurrentLinkedQueue()
        val geslaagd: AtomicInteger = AtomicInteger(0)
        val mislukt: AtomicInteger = AtomicInteger(0)
    }
}

data class BerichtenPage(
    val berichten: List<BerichtSamenvatting>,
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
