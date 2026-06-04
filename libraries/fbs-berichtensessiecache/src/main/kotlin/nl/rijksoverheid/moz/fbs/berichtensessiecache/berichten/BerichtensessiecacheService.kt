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
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnBerichtenResponse
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClient
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
// 4 collaborators + 6 @ConfigProperty-waarden verspreid over 3 config-prefixen; groeperen in
// een config-object zou een kunstmatige producer-indirectie vergen zonder leesbaarheidswinst —
// CDI-constructor-injectie per property is hier het idioom.
@Suppress("LongParameterList")
internal class BerichtensessiecacheService(
    private val berichtenCache: BerichtenCache,
    private val clientFactory: MagazijnClientFactory,
    private val berichtValidator: BerichtValidator,
    private val resolver: MagazijnResolver,
    @param:ConfigProperty(name = "profiel.resolver.inner-timeout-seconds", defaultValue = "18")
    private val innerTimeoutSeconds: Long,
    @param:ConfigProperty(name = "profiel.resolver.outer-await-seconds", defaultValue = "25")
    private val outerAwaitSeconds: Long,
    // Heap-bescherming tegen een rogue/defect magazijn: cap op het AANTAL berichten, toegepast
    // ná deserialisatie (begrenst de cache-groei + verdere verwerking). LET OP: dit is GEEN
    // byte-cap vóór deserialisatie — `quarkus.http.limits.max-body-size` geldt enkel voor
    // INKOMENDE requests naar deze service, niet voor deze outbound magazijn-respons. De
    // grootte van de inkomende magazijn-respons wordt alleen begrensd door de read-timeout
    // (leesduur, niet bytes). Een harde outbound byte-cap is bewust niet toegevoegd: magazijn-
    // URLs komen uit eigen, TLS-bewaakte config (geen attacker-endpoints) en realistische
    // responses zijn enkele KB — geen speculatieve payload-cap zonder concrete
    // productieaanleiding. Niet stilzwijgend verhogen zonder load-evidence.
    @param:ConfigProperty(name = "berichtensessiecache.max-berichten-per-magazijn", defaultValue = "200")
    private val maxBerichtenPerMagazijn: Int,
    // Per-magazijn query-timeout (Mutiny `ifNoItem`): primaire TIMEOUT-signaalbron richting
    // de client. MOET kleiner zijn dan magazijn-client.read-timeout-ms zodat dit als eerste
    // aanslaat en een TIMEOUT-event oplevert i.p.v. een ruwe client-fout. valideerTimeouts()
    // dwingt die invariant bij startup af.
    @param:ConfigProperty(name = "berichtensessiecache.magazijn-query-timeout-seconds", defaultValue = "10")
    private val magazijnQueryTimeoutSeconds: Long,
    // Read-timeout van de magazijn-client; MagazijnClientFactory past dezelfde property toe als
    // socket-timeout. Hier enkel geïnjecteerd om de invariant read-timeout > query-timeout bij
    // startup te kruisvalideren. Sleutel + default komen uit één gedeelde constante
    // (MagazijnClientFactory.READ_TIMEOUT_MS_*) zodat de gevalideerde waarde niet kan afwijken
    // van de toegepaste.
    @param:ConfigProperty(
        name = MagazijnClientFactory.READ_TIMEOUT_MS_PROPERTY,
        defaultValue = MagazijnClientFactory.READ_TIMEOUT_MS_DEFAULT,
    )
    private val magazijnReadTimeoutMs: Long,
    // Max blocking-await op één Redis-commando vanaf de @Blocking worker-thread tijdens
    // ophaal-orkestratie (lock-acquire, status-updates, cleanup). Een overschreden await-deadline
    // faalt de lopende ophaalstap (await-timeout → 503; het cleanup-pad slikt de fout en leunt
    // op de Redis-TTL). Losse knop: geen invariant met de magazijn-/profiel-timeouts.
    @param:ConfigProperty(name = "berichtensessiecache.cache-await-timeout-seconds", defaultValue = "5")
    private val cacheAwaitTimeoutSeconds: Long,
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

        // De per-magazijn query-timeout (ifNoItem) MOET vóór de socket-read-timeout aanslaan,
        // anders krijgt de client een ruwe client-fout i.p.v. een net TIMEOUT-event; de
        // read-timeout blijft het vangnet als de query-timeout om welke reden dan ook niet vuurt.
        require(magazijnReadTimeoutMs > magazijnQueryTimeoutSeconds * 1000) {
            "magazijn-client.read-timeout-ms ($magazijnReadTimeoutMs) moet groter zijn dan " +
                "berichtensessiecache.magazijn-query-timeout-seconds × 1000 (${magazijnQueryTimeoutSeconds * 1000})"
        }

        // Ondergrens: een 0/negatieve timeout schakelt de bescherming stil uit. Mutiny's
        // `await().atMost(ZERO)` wacht onbegrensd (blokkeert de @Blocking thread tot de TTL,
        // 409 voor de hele sessie) en `ifNoItem().after(ZERO)` vuurt direct. De ordening-checks
        // hierboven borgen outer>inner>0 en read>query>0 transitief; cache-await staat los.
        require(innerTimeoutSeconds > 0) {
            "profiel.resolver.inner-timeout-seconds ($innerTimeoutSeconds) moet groter zijn dan 0"
        }

        require(magazijnQueryTimeoutSeconds > 0) {
            "berichtensessiecache.magazijn-query-timeout-seconds ($magazijnQueryTimeoutSeconds) moet groter zijn dan 0"
        }

        require(cacheAwaitTimeoutSeconds > 0) {
            "berichtensessiecache.cache-await-timeout-seconds ($cacheAwaitTimeoutSeconds) moet groter zijn dan 0"
        }

        log.infof(
            "Timeouts: profiel inner=%ds outer=%ds; magazijn query-timeout=%ds read-timeout=%dms; cache-await=%ds",
            innerTimeoutSeconds,
            outerAwaitSeconds,
            magazijnQueryTimeoutSeconds,
            magazijnReadTimeoutMs,
            cacheAwaitTimeoutSeconds,
        )
    }

    fun getBerichten(page: Int, pageSize: Int, ontvanger: Identificatienummer, afzender: String?, map: String? = null): Uni<BerichtenPagina> {
        log.debugf("Ophalen berichten uit cache: page=%d, pageSize=%d", page, pageSize)
        val key = BerichtenCache.cacheKey(ontvanger)

        // Cache-miss (null: nog nooit opgehaald óf TTL verlopen) en "opgehaald, 0 berichten"
        // collapsen bewust naar dezelfde lege pagina. Het onderscheid loopt via een aparte
        // bron: de caller raadpleegt getAggregationStatus om "nog ophalen / niets in cache"
        // te onderscheiden van een afgeronde lege ophaling.
        return berichtenCache.getPage(key, page, pageSize, afzender, ontvanger, map)
            .map { it ?: BerichtenPagina(emptyList(), page, pageSize, 0L, 0) }
    }

    fun getAggregationStatus(ontvanger: Identificatienummer): Uni<AggregationStatus?> {
        val key = BerichtenCache.cacheKey(ontvanger)

        return berichtenCache.getAggregationStatus(key)
    }

    fun getBerichtById(berichtId: UUID, ontvanger: Identificatienummer): Uni<Bericht?> {
        log.debugf("Ophalen bericht uit cache: %s", berichtId)

        return berichtenCache.getById(berichtId, ontvanger)
    }

    fun zoekBerichten(q: String, page: Int, pageSize: Int, ontvanger: Identificatienummer, afzender: String?, map: String? = null): Uni<BerichtenPagina> {
        // q is user-input zonder CRLF-filter op spec-niveau; loggen van q.length voorkomt
        // log-injectie via newline-payloads. Voor diepere debug staat de query elders in
        // RediSearch-server-log.
        log.debugf("Zoeken berichten via RediSearch: q.length=%d, page=%d, pageSize=%d", q.length, page, pageSize)

        return berichtenCache.search(ontvanger, q, page, pageSize, afzender, map)
    }

    fun updateBerichtMetadata(berichtId: UUID, ontvanger: Identificatienummer, status: String?, map: String?): Uni<Bericht?> {
        log.debugf("Bijwerken bericht: berichtId=%s, status=%s, map=%s", berichtId, status, map)

        return berichtenCache.updateBerichtMetadata(berichtId, ontvanger, status, map)
    }

    fun createBericht(bericht: Bericht, ontvanger: Identificatienummer): Uni<Bericht> {
        log.debugf("Toevoegen bericht aan cache: berichtId=%s", bericht.berichtId)
        val gevalideerd = berichtValidator.valideer(bericht)

        return berichtenCache.createBericht(gevalideerd, ontvanger).replaceWith(gevalideerd)
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

        val bezigStatus = AggregationStatus(
            status = OphalenStatus.BEZIG,
            totaalMagazijnen = 0,
        )
        val wasSet = verwerfOphaalLock(cacheKey, bezigStatus)

        if (!wasSet) {
            throw WebApplicationException(
                "Berichten worden momenteel al opgehaald voor deze ontvanger. Wacht tot het ophalen is afgerond.",
                409,
            )
        }

        val resolvedIds = when (val uitkomst = resolveMagazijnen(ontvanger, cacheKey)) {
            is ResolveUitkomst.FoutStream -> return uitkomst.events
            is ResolveUitkomst.Ids -> uitkomst.ids
        }

        val clients = bepaalClients(resolvedIds, cacheKey)

        // Geen magazijnen → lege resultaten + GEREED-status.
        if (clients.isEmpty()) {
            return legeResultaten(cacheKey)
        }

        // synchronizedList(ArrayList) i.p.v. ConcurrentLinkedQueue: Mutiny's merging-stream
        // kan callbacks parallel emitten, dus sync is nodig. Geen lock-free CAS per Node
        // (queue) maar wel goedkoop blocking; payload-size is paar honderd berichten.
        val alleBerichten: MutableList<Bericht> = Collections.synchronizedList(ArrayList())
        val geslaagd = AtomicInteger(0)
        val mislukt = AtomicInteger(0)

        zetBezigStatusMetTotaal(cacheKey, bezigStatus, clients.size)

        val ontvangerString = ontvanger.toCanonicalString()

        val magazijnStreams = clients.map { (magazijnId, client) ->
            bouwMagazijnStream(magazijnId, client, ontvangerString, alleBerichten, geslaagd, mislukt)
        }

        // Aggregatie-pipeline: draait onafhankelijk van de SSE-client door tot voltooiing.
        return Multi.createBy().concatenating().streams(
            Multi.createBy().merging().streams(magazijnStreams),
            aggregeerEnSlaOp(cacheKey, clients.size, alleBerichten, geslaagd, mislukt),
        )
    }

    /**
     * Atomaire lock via trySetAggregationStatus (SET NX EX in één commando): voorkom
     * concurrent ophalen voor dezelfde ontvanger. Blokkerende await() is hier bewust:
     * het aanroepende endpoint (BerichtenOphalenResource) is @Blocking gemarkeerd. De
     * lock-check moet synchroon afgerond zijn voordat de Multi-stream gestart wordt,
     * omdat de 409-response anders niet meer mogelijk is.
     * Try/catch: bij Redis-fout (timeout, connection drop) kan de lock-set partial
     * geslaagd zijn (lockKey wel, statusKey niet). De cache-laag compenseert intern,
     * maar caller-side cleanup als defense-in-depth voor await-level failures.
     */
    private fun verwerfOphaalLock(cacheKey: String, bezigStatus: AggregationStatus): Boolean {
        return try {
            berichtenCache.trySetAggregationStatus(cacheKey, bezigStatus)
                .await().atMost(Duration.ofSeconds(cacheAwaitTimeoutSeconds))
        } catch (ex: Exception) {
            throw naarLockAcquireFout(ex, cacheKey)
        }
    }

    /** Logt + ruimt de lock op en mapt de geclassificeerde lock-acquire-fout naar een HTTP-fout. */
    private fun naarLockAcquireFout(ex: Exception, cacheKey: String): WebApplicationException {
        // Cause-walking: Mutiny's blocking-await wrapt upstream-failures, dus directe
        // instanceof matched de echte fout niet.
        val errorId = UUID.randomUUID()

        return when (classifyLockAcquireError(ex)) {
            LockAcquireError.INTERRUPTED -> {
                // Herstel interrupt-flag voor graceful pod-shutdown; 503 (transient), geen eigen-bug.
                Thread.currentThread().interrupt()
                log.warnf(ex, "(errorId=%s) Lock-acquire onderbroken voor key=%s", errorId, cacheKey)
                cleanupLockMetFoutStatus(cacheKey, "lock-acquire interrupted", errorId)
                WebApplicationException("Service shutdown tijdens ophaalstart", 503)
            }
            LockAcquireError.JSON_SERIALIZATION -> {
                log.errorf(ex, "(errorId=%s) Lock-acquire JSON-serialisatie-fout voor key=%s", errorId, cacheKey)
                cleanupLockMetFoutStatus(cacheKey, "json-serialisatie-fout bij lock-acquire", errorId)
                WebApplicationException("Interne fout bij serialisatie status", 500)
            }
            LockAcquireError.TIMEOUT -> {
                log.errorf(ex, "(errorId=%s) Lock-acquire timeout voor key=%s", errorId, cacheKey)
                cleanupLockMetFoutStatus(cacheKey, "lock-acquire timeout", errorId)
                WebApplicationException("Cache niet bereikbaar bij ophaalstart (timeout)", 503)
            }
            LockAcquireError.IO_FAULT -> {
                log.errorf(ex, "(errorId=%s) Lock-acquire I/O-fout voor key=%s (cause=%s)", errorId, cacheKey, ex.javaClass.simpleName)
                cleanupLockMetFoutStatus(cacheKey, "lock-acquire I/O-fout: ${ex.javaClass.simpleName}", errorId)
                WebApplicationException("Cache niet bereikbaar bij ophaalstart", 503)
            }
            LockAcquireError.UNEXPECTED -> {
                log.errorf(ex, "(errorId=%s) Lock-acquire onverwachte fout voor key=%s (cause=%s)", errorId, cacheKey, ex.javaClass.simpleName)
                cleanupLockMetFoutStatus(cacheKey, "lock-acquire onverwacht: ${ex.javaClass.simpleName}", errorId)
                WebApplicationException("Interne fout bij ophaalstart", 500)
            }
        }
    }

    // Resolver-uitkomst: magazijn-IDs, óf een direct te retourneren OPHALEN_FOUT-stream
    // (CONFIG_DRIFT) — dat onderscheid kan niet via een exception lopen omdat de SSE-caller
    // dan een 5xx zou geven i.p.v. een zichtbaar fout-event.
    private sealed interface ResolveUitkomst {
        data class Ids(val ids: Set<String>) : ResolveUitkomst

        data class FoutStream(val events: Multi<MagazijnEvent>) : ResolveUitkomst
    }

    private fun resolveMagazijnen(ontvanger: Identificatienummer, cacheKey: String): ResolveUitkomst {
        return try {
            // Outer-budget = inner-timeout + marge (cross-validatie in valideerTimeouts),
            // zodat de outer nooit aanslaat vóór de inner — anders verliest de caller
            // de juiste foutclassificatie (timeout vs onbereikbaar).
            val ids = resolver.resolve(ontvanger).await().atMost(Duration.ofSeconds(outerAwaitSeconds))

            ResolveUitkomst.Ids(ids)
        } catch (ex: ProfielServiceFoutException) {
            // ex.errorId doorgeven zodat cleanup-log dezelfde id draagt als mapper-respons.
            cleanupLockMetFoutStatus(cacheKey, "profiel-service-fout: ${ex.categorie.name}", ex.errorId)

            // CONFIG_DRIFT: eigen-config-fout, niet Profiel-storing — emit zichtbare
            // OPHALEN_FOUT i.p.v. 503 zodat client weet "geen ophaling mogelijk", niet
            // "Profiel offline, retry over 30s". Cache wordt NIET overschreven met empty.
            if (ex.categorie != ProfielServiceFoutException.Categorie.CONFIG_DRIFT) throw ex

            // Riem-en-bretels: gestructureerd `referentie`-veld + suffix in tekst.
            // Single source `ref` voorkomt format-drift tussen beide velden.
            val ref = ex.errorId.toString()

            ResolveUitkomst.FoutStream(
                Multi.createFrom().item(
                    MagazijnEvent(
                        event = EventType.OPHALEN_FOUT,
                        totaalMagazijnen = 0,
                        foutmelding = "Geen ophaling mogelijk: configuratie-mismatch — contact beheerder (ref: $ref)",
                        referentie = ref,
                    ),
                ),
            )
        } catch (ex: Exception) {
            gooiResolverFout(ex, cacheKey)
        }
    }

    private fun gooiResolverFout(ex: Exception, cacheKey: String): Nothing {
        // Cause-walking via causeChain() (eenmaal materialiseren) — Mutiny wrapt
        // InterruptedException/TimeoutException, directe instanceof matched niet.
        // isInterrupted (non-destructive) — Thread.interrupted() zou de flag wissen
        // ook in niet-interrupt-branches.
        val chain = ex.causeChain()
        val wasInterrupted = Thread.currentThread().isInterrupted ||
            chain.hasCauseOf(InterruptedException::class.java)
        val isOuterTimeout = chain.hasCauseOf(io.smallrye.mutiny.TimeoutException::class.java) ||
            chain.hasCauseOf(java.util.concurrent.TimeoutException::class.java)

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

    /**
     * De resolver mag alleen magazijn-IDs teruggeven die de factory kent;
     * contract van MagazijnResolver. Een onbekende ID is een bug (drift tussen
     * resolver-config en magazijn-config) en moet hard falen, niet stil leeg-degraderen.
     * Cleanup vóór de throw: zonder dit blijft de lock tot TTL hangen en blokkeert
     * legitieme retries na de drift-fix.
     */
    private fun bepaalClients(resolvedIds: Set<String>, cacheKey: String): Map<String, MagazijnClient> {
        val allClients = clientFactory.getAllClients()
        val onbekend = resolvedIds - allClients.keys

        if (onbekend.isNotEmpty()) {
            val errorId = UUID.randomUUID()

            log.errorf("(errorId=%s) Drift: resolver leverde onbekende magazijn-IDs %s voor key=%s", errorId, onbekend, cacheKey)
            cleanupLockMetFoutStatus(cacheKey, "drift: resolver leverde onbekende magazijn-IDs", errorId)
            throw IllegalArgumentException("Resolver leverde onbekende magazijn-IDs: $onbekend")
        }

        return allClients.filterKeys { it in resolvedIds }
    }

    // Happy: alleen statusKey overschrijven; lockKey blijft tot GEREED/FOUT-schrijfactie.
    // Fout: cleanupLockMetFoutStatus schrijft FOUT (geeft lock vrij via interne del).
    private fun zetBezigStatusMetTotaal(cacheKey: String, bezigStatus: AggregationStatus, totaalMagazijnen: Int) {
        try {
            berichtenCache.updateAggregationStatus(
                cacheKey,
                bezigStatus.copy(totaalMagazijnen = totaalMagazijnen),
            ).await().atMost(Duration.ofSeconds(cacheAwaitTimeoutSeconds))
        } catch (ex: Exception) {
            val errorId = UUID.randomUUID()

            log.errorf(ex, "(errorId=%s) Update aggregatie-status (BEZIG, totaalMagazijnen=%d) mislukt voor key=%s", errorId, totaalMagazijnen, cacheKey)
            cleanupLockMetFoutStatus(cacheKey, "update-aggregatie-status mislukt", errorId)
            throw WebApplicationException("Cache niet bereikbaar tijdens initialisatie ophaalsessie.", 503)
        }
    }

    /**
     * Lege-magazijn-pad: cache overschrijven met lege lijst (zodat stale data uit eerdere
     * sessies niet zichtbaar blijft via GET-endpoints) + GEREED-status, gevolgd door één
     * OPHALEN_GEREED-event. Bij een store-fout een OPHALEN_FOUT-event i.p.v. mid-stream HTTP-500.
     *
     * LET OP (bekende beperking): een lege resolver-set kan ook ontstaan uit een transient
     * Profiel-404 of base-path-drift (zie ProfielMagazijnResolver 404-tak). In dat geval
     * overschrijft dit pad geldige eerder-gecachte berichten met een lege lijst — niet te
     * onderscheiden van een echte opt-out tot de upstream-404-semantiek is aangescherpt;
     * detectie loopt tot dan via de Profiel-404-rate-alert (docs/operations/profiel-404-alert.md).
     */
    private fun legeResultaten(cacheKey: String): Multi<MagazijnEvent> {
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
                .await().atMost(Duration.ofSeconds(cacheAwaitTimeoutSeconds))
        } catch (ex: Exception) {
            val errorId = UUID.randomUUID()
            val ref = errorId.toString()

            log.errorf(ex, "(errorId=%s) Store-fout bij lege magazijn-set voor key=%s", errorId, cacheKey)
            cleanupLockMetFoutStatus(cacheKey, "store-fout bij lege magazijn-set", errorId)
            // SSE-stream is al actief op dit punt; OPHALEN_FOUT-event geeft de client
            // dezelfde UX als het aggregatie-faalpad i.p.v. een mid-stream HTTP-500.
            // Referentie zowel in foutmelding-tekst ("(ref: ...)") als in het
            // gestructureerde `referentie`-veld, voor UIs die het veld nog niet renderen.
            return Multi.createFrom().item(
                MagazijnEvent(
                    event = EventType.OPHALEN_FOUT,
                    totaalMagazijnen = 0,
                    foutmelding = "Interne fout bij opslaan resultaten (ref: $ref)",
                    referentie = ref,
                ),
            )
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

    /**
     * Bouwt de event-stream voor één magazijn: een GESTART-event gevolgd door het
     * VOLTOOID-event (OK/TIMEOUT/FOUT). De per-magazijn query-timeout levert het primaire
     * TIMEOUT-signaal; de berichten-cap beschermt de heap tegen een rogue magazijn; de
     * fout-classificatie ([classifyMagazijnFault]) bepaalt log-niveau + eindgebruiker-melding.
     * Geslaagde berichten worden in [alleBerichten] verzameld; [geslaagd]/[mislukt] tellen
     * de uitkomst voor de eind-aggregatie.
     */
    private fun bouwMagazijnStream(
        magazijnId: String,
        client: MagazijnClient,
        ontvangerString: String,
        alleBerichten: MutableList<Bericht>,
        geslaagd: AtomicInteger,
        mislukt: AtomicInteger,
    ): Multi<MagazijnEvent> {
        val naam = clientFactory.getNaam(magazijnId)

        val gestartEvent = MagazijnEvent(
            event = EventType.MAGAZIJN_BEVRAGING_GESTART,
            magazijnId = magazijnId,
            naam = naam,
        )

        val resultUni = Uni.createFrom().item { client }
            .onItem().transform { c -> c.getBerichten(ontvangerString, null) }
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .ifNoItem().after(Duration.ofSeconds(magazijnQueryTimeoutSeconds)).fail()
            .map<MagazijnResult> { response -> naarMagazijnResult(response, magazijnId, naam) }
            .onFailure(Exception::class.java).recoverWithItem { error ->
                val fault = classifyMagazijnFault(error)

                logMagazijnFault(error, magazijnId, naam, fault)
                MagazijnResult.Failure(magazijnId, naam, error, fault)
            }

        val resultStream = resultUni.toMulti().map { result ->
            naarVoltooidEvent(result, alleBerichten, geslaagd, mislukt)
        }

        return Multi.createBy().concatenating().streams(
            Multi.createFrom().item(gestartEvent),
            resultStream,
        )
    }

    private fun naarMagazijnResult(response: MagazijnBerichtenResponse, magazijnId: String, naam: String?): MagazijnResult {
        if (response.berichten.size > maxBerichtenPerMagazijn) {
            // ErrorF nodig voor Loki-alert-routing; SSE-event alleen gaat niet naar logs.
            log.errorf(
                "Magazijn %s (%s) overschreed berichten-cap: %d > %d",
                magazijnId, naam, response.berichten.size, maxBerichtenPerMagazijn,
            )
            val foutMessage = "Magazijn leverde meer berichten dan toegestaan (${response.berichten.size} > $maxBerichtenPerMagazijn)"

            return MagazijnResult.Failure(magazijnId, naam, MagazijnResponseOverflow(foutMessage), MagazijnFault.OVERFLOW)
        }

        // Magazijn levert MagazijnBericht-DTO's; vlak af naar het cache-domein
        // (toBericht) en valideer defensief (BerichtLimieten). Eén invalid bericht
        // mag de batch niet killen — drop het stuk en log warn i.p.v. de hele
        // magazijn-bevraging te laten falen.
        val berichten = response.berichten
            .map { it.toBericht(magazijnId) }
            .mapNotNull { berichtValidator.valideerOrLogAndDrop(it) }

        return MagazijnResult.Success(magazijnId, naam, berichten)
    }

    /** Verwerkt het per-magazijn-resultaat in de tellers en mapt het naar het VOLTOOID-event. */
    private fun naarVoltooidEvent(
        result: MagazijnResult,
        alleBerichten: MutableList<Bericht>,
        geslaagd: AtomicInteger,
        mislukt: AtomicInteger,
    ): MagazijnEvent = when (result) {
        is MagazijnResult.Success -> {
            alleBerichten.addAll(result.berichten)
            geslaagd.incrementAndGet()
            MagazijnEvent(
                event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID,
                magazijnId = result.magazijnId,
                naam = result.naam,
                status = MagazijnStatus.OK,
                aantalBerichten = result.berichten.size,
            )
        }
        is MagazijnResult.Failure -> {
            mislukt.incrementAndGet()
            MagazijnEvent(
                event = EventType.MAGAZIJN_BEVRAGING_VOLTOOID,
                magazijnId = result.magazijnId,
                naam = result.naam,
                status = if (result.fault == MagazijnFault.TIMEOUT) MagazijnStatus.TIMEOUT else MagazijnStatus.FOUT,
                foutmelding = foutmeldingVoor(result.fault),
            )
        }
    }

    private fun foutmeldingVoor(fault: MagazijnFault): String = when (fault) {
        MagazijnFault.TIMEOUT -> "Magazijn reageerde niet binnen de timeout"
        MagazijnFault.MALFORMED -> "Magazijn leverde onleesbare respons (mogelijk schema-drift, contact beheerder)"
        MagazijnFault.OVERFLOW -> "Magazijn leverde te veel berichten (responsgrootte overschreden, contact beheerder)"
        MagazijnFault.HTTP_5XX -> "Magazijn tijdelijk niet bereikbaar"
        MagazijnFault.HTTP_4XX -> "Magazijn heeft de aanvraag geweigerd (configuratiefout, contact beheerder)"
        // BIO 14.1.3: generiek bericht aan eindgebruiker; technisch onderscheid alleen in log.
        MagazijnFault.INTERNAL_BUG, MagazijnFault.NETWORK -> "Magazijn kon niet geraadpleegd worden"
    }

    /**
     * Slaat de verzamelde berichten + GEREED-status op (parallel: verschillende keys, geen
     * ordering-afhankelijkheid → bespaart 1 Redis-RTT) en emit het afsluitende
     * OPHALEN_GEREED-event. Bij een cache-fout een OPHALEN_FOUT-event i.p.v. een mid-stream
     * HTTP-500 (de SSE-stream loopt dan al); een dubbele cache-fout krijgt een
     * `[ALERT cache_doublefail]`-marker voor alert-routing.
     */
    private fun aggregeerEnSlaOp(
        cacheKey: String,
        totaalMagazijnen: Int,
        alleBerichten: MutableList<Bericht>,
        geslaagd: AtomicInteger,
        mislukt: AtomicInteger,
    ): Multi<MagazijnEvent> =
        Uni.createFrom().voidItem()
            .chain { _ ->
                val berichten = alleBerichten.toList()
                val status = AggregationStatus(
                    status = OphalenStatus.GEREED,
                    totaalMagazijnen = totaalMagazijnen,
                    geslaagd = geslaagd.get(),
                    mislukt = mislukt.get(),
                )

                // Parallel: store(berichten) en storeAggregationStatus(GEREED) hebben
                // verschillende keys en geen ordering-afhankelijkheid. Bespaart 1 Redis-RTT
                // t.o.v. sequentiële .chain (consistent met het lege-magazijn-pad).
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
                            totaalMagazijnen = totaalMagazijnen,
                        )
                    }
            }
            .onFailure(Exception::class.java).recoverWithUni { error ->
                herstelNaAggregatieCacheFout(error, cacheKey, totaalMagazijnen, alleBerichten, geslaagd, mislukt)
            }
            .toMulti()

    /**
     * Eerste fout = store(berichten) of storeAggregationStatus(GEREED) faalde;
     * cacheKey + counters + errorId in log zodat ops kan correleren naar
     * specifieke sessie én naar het MagazijnEvent.referentie veld.
     */
    private fun herstelNaAggregatieCacheFout(
        error: Throwable,
        cacheKey: String,
        totaalMagazijnen: Int,
        alleBerichten: MutableList<Bericht>,
        geslaagd: AtomicInteger,
        mislukt: AtomicInteger,
    ): Uni<MagazijnEvent> {
        val errorId = UUID.randomUUID()
        val ref = errorId.toString()

        log.errorf(
            error,
            "(errorId=%s) Fout bij opslaan in cache na aggregatie (key=%s, berichten=%d, geslaagd=%d, mislukt=%d)",
            errorId, cacheKey, alleBerichten.size, geslaagd.get(), mislukt.get(),
        )
        val foutStatus = AggregationStatus(
            status = OphalenStatus.FOUT,
            totaalMagazijnen = totaalMagazijnen,
            geslaagd = geslaagd.get(),
            mislukt = mislukt.get(),
        )

        return berichtenCache.storeAggregationStatus(cacheKey, foutStatus)
            // FATAL + ALERT-marker: dubbele Redis-fout = cache effectief onbruikbaar
            // voor deze sessie. Lock blijft tot Redis-TTL hangen. Het prefix wordt
            // door log-aggregator (Loki/CloudWatch) gefilterd richting alert-routing.
            .onFailure().invoke { e ->
                log.fatalf(
                    e,
                    "[ALERT cache_doublefail] (errorId=%s) Cache-write FAIL/FAIL (key=%s, berichten=%d, geslaagd=%d, mislukt=%d): Redis onbruikbaar voor sessie, lock leunt op TTL",
                    errorId, cacheKey, alleBerichten.size, geslaagd.get(), mislukt.get(),
                )
            }
            .onFailure().recoverWithNull()
            // Referentie zowel in foutmelding-tekst ("(ref: ...)") als in het
            // gestructureerde `referentie`-veld, voor UIs zonder veld-rendering.
            // Meld expliciet dat de eerder per-magazijn getoonde resultaten niet
            // bewaard zijn: de VOLTOOID-events zijn al geëmit, maar de cache-write
            // faalde, dus de client moet opnieuw ophalen i.p.v. te denken dat de
            // resultaten beschikbaar zijn via GET /berichten.
            .replaceWith(
                MagazijnEvent(
                    event = EventType.OPHALEN_FOUT,
                    geslaagd = geslaagd.get(),
                    mislukt = mislukt.get(),
                    totaalMagazijnen = totaalMagazijnen,
                    foutmelding = "Resultaten konden niet worden opgeslagen; haal opnieuw op (ref: $ref)",
                    referentie = ref,
                )
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
        errorId: UUID = UUID.randomUUID(),
    ) {
        try {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                AggregationStatus(status = OphalenStatus.FOUT),
            ).await().atMost(Duration.ofSeconds(cacheAwaitTimeoutSeconds))

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
     * Materialiseert de cause-chain éénmaal als list (cycle-safe via IdentityHashMap,
     * depth-cap als defense-in-depth tegen pathologische `cause`-getters). Callers
     * (classifyMagazijnFault/classifyLockAcquireError) doen meerdere instanceof-checks
     * tegen dit resultaat i.p.v. meerdere walks → één warnf max bij depth-cap.
     */
    private fun Throwable.causeChain(): List<Throwable> {
        val result = ArrayList<Throwable>(4)
        val seen = java.util.IdentityHashMap<Throwable, Unit>()
        var cur: Throwable? = this

        while (cur != null && seen.put(cur, Unit) == null) {
            result.add(cur)
            if (result.size >= MAX_CAUSE_DEPTH) {
                // Chain-namen meeloggen — anders kan on-call niet zien welke wrapper-types
                // de chain dieper dan depth-cap maakten (misclassificatie als INTERNAL_BUG).
                log.warnf(
                    "Cause-chain depth-cap (%d) bereikt in root=%s, chain=%s — classificatie kan onnauwkeurig zijn",
                    MAX_CAUSE_DEPTH,
                    this::class.java.simpleName,
                    result.joinToString(",") { it::class.java.simpleName },
                )
                break
            }
            cur = cur.cause
        }

        return result
    }

    private fun List<Throwable>.findCauseOfClass(cls: Class<*>): Throwable? = firstOrNull { cls.isInstance(it) }

    // Safe cast: findCauseOfClass filtert al op cls.isInstance, dus `as?` levert
    // nooit null voor een match en vermijdt de unchecked-cast die `as T?` zou geven.
    private inline fun <reified T : Throwable> List<Throwable>.findCauseOf(): T? =
        findCauseOfClass(T::class.java) as? T

    private fun List<Throwable>.hasCauseOf(cls: Class<*>): Boolean = findCauseOfClass(cls) != null

    private companion object {
        private const val MAX_CAUSE_DEPTH = 32
    }

    internal enum class LockAcquireError { JSON_SERIALIZATION, TIMEOUT, IO_FAULT, INTERRUPTED, UNEXPECTED }

    // Internal voor directe unit-test van classificatie + cycle-/depth-cap-gedrag.
    internal fun classifyLockAcquireError(ex: Throwable): LockAcquireError {
        val chain = ex.causeChain()

        return when {
            chain.hasCauseOf(InterruptedException::class.java) -> LockAcquireError.INTERRUPTED
            chain.hasCauseOf(JsonProcessingException::class.java) -> LockAcquireError.JSON_SERIALIZATION
            chain.hasCauseOf(io.smallrye.mutiny.TimeoutException::class.java) ||
                chain.hasCauseOf(java.util.concurrent.TimeoutException::class.java) -> LockAcquireError.TIMEOUT
            chain.hasCauseOf(java.io.IOException::class.java) -> LockAcquireError.IO_FAULT
            else -> LockAcquireError.UNEXPECTED
        }
    }

    // Internal voor directe unit-test van de classificatie-tabel (zie service-test).
    internal fun classifyMagazijnFault(error: Throwable): MagazijnFault {
        // Cause-walking eenmalig via causeChain(); meerdere instanceof-checks tegen
        // dezelfde list — voorkomt log-storm van depth-cap warnf bij elke check.
        val chain = error.causeChain()
        val webEx = chain.findCauseOf<WebApplicationException>()

        return when {
            chain.hasCauseOf(MagazijnResponseOverflow::class.java) -> MagazijnFault.OVERFLOW
            chain.hasCauseOf(TimeoutException::class.java) -> MagazijnFault.TIMEOUT
            chain.hasCauseOf(JsonProcessingException::class.java) -> MagazijnFault.MALFORMED
            chain.hasCauseOf(ConnectException::class.java) -> MagazijnFault.NETWORK
            webEx != null -> {
                val status = webEx.response?.status ?: 0

                when {
                    status in 500..599 -> MagazijnFault.HTTP_5XX
                    status >= 400 -> MagazijnFault.HTTP_4XX
                    // WAE zonder status = raw WAE("oeps") zonder Response → eigen-bug.
                    else -> MagazijnFault.INTERNAL_BUG
                }
            }
            chain.hasCauseOf(ProcessingException::class.java) -> MagazijnFault.NETWORK
            // Annulering (bv. bij pod-shutdown of upstream-cancel) is geen magazijn-bug.
            // Zonder deze tak valt het in `else -> INTERNAL_BUG` en logt het errorf, wat
            // vals-positieve alert-ruis geeft. NETWORK logt warnf met een neutrale melding.
            chain.hasCauseOf(java.util.concurrent.CancellationException::class.java) -> MagazijnFault.NETWORK
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
