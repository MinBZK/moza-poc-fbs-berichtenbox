package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.core.JsonProcessingException
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.CircuitActie
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.GepagineerdeBerichten
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnAggregatieBulkhead
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnBericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnCircuitBreaker
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnCircuitOpenException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClient
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClientFactory
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnFault
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnOverbelastException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnPaginaLezer
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResolver
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResponseOverflow
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResult
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.circuitActieVoor
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.ConnectException
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
    // Concurrency-bulkhead voor de blokkerende magazijn-aggregatie-calls: begrenst hoeveel van de
    // gedeelde default-worker-pool tegelijk een magazijn bevragen, zodat een trage leverancier niet
    // alle threads opsoupeert en andere endpoints blokkeert — zonder een eigen pool (die de
    // Vert.x-duplicated-context voor de downstream-Redis-writes zou verliezen).
    private val bulkhead: MagazijnAggregatieBulkhead,
    // Per-magazijn circuit breaker: slaat een magazijn na herhaalde storingen tijdelijk over,
    // zodat een dood magazijn niet onnodig bulkhead-permits bezet houdt.
    private val circuitBreaker: MagazijnCircuitBreaker,
    private val paginaLezer: MagazijnPaginaLezer,
) {
    private val log = Logger.getLogger(BerichtensessiecacheService::class.java)

    // Hetzelfde getal als de `ifNoItem`-timeout hieronder; de lus bewaakt het zelf.
    private val magazijnQueryBudget: Duration get() = Duration.ofSeconds(magazijnQueryTimeoutSeconds)

    /**
     * Dwingt bean-instantiatie — en daarmee [valideerTimeouts] — af bij het opstarten. Zonder deze
     * observer maakt ArC deze bean pas aan bij het eerste request (het enige pad ernaartoe loopt
     * via de facade), en dan komt een ongeldige timeout-combinatie pas aan het licht wanneer een
     * gebruiker een ophaalronde start: pod gestart, readiness groen, rollout geslaagd, en daarna
     * faalt élke ophaalronde. De ondergrenzen in `MagazijnClientFactory` vangen dat niet — die
     * kent de query-timeout niet, en juist de verhouding read > query is hier de invariant.
     */
    fun onStartup(@Observes event: StartupEvent) = Unit

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
        // In seconden vergelijken i.p.v. de query-timeout naar milliseconden te tillen: dat
        // laatste loopt bij een absurde waarde over naar negatief en laat de check dan juist
        // passeren — precies het stil uitzetten van een bescherming dat hier voorkomen wordt.
        require(magazijnReadTimeoutMs / 1000 > magazijnQueryTimeoutSeconds) {
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

        // Het wachtbudget van het bulkhead MOET minstens één volledige magazijn-call overleven.
        // Een permit komt pas vrij als de call die hem houdt afgerond is, en die mag tot de
        // query-timeout duren. Is het budget korter, dan verliest een bevraging die aanklopt terwijl
        // alle permits door trage calls bezet zijn haar budget vóórdat er ook maar één permit kán
        // vrijkomen — en dan is de wachtrij voor die bevraging alsnog een zeef.
        // In seconden delen i.p.v. de timeout naar milliseconden tillen, om dezelfde reden als
        // hierboven: die vermenigvuldiging loopt bij een absurde waarde over naar negatief en laat
        // de check dan juist passeren. De boodschap noemt beide grenzen, want het wachtbudget kan
        // niet boven zijn eigen plafond — bij een hogere query-timeout is dát de knop die moet
        // zakken, en anders kost het de operator een tweede deploy om daarachter te komen.
        require(bulkhead.wachtbudgetMs() / MILLIS_PER_SECOND >= magazijnQueryTimeoutSeconds) {
            "${MagazijnAggregatieBulkhead.MAX_WACHTTIJD_MS_PROPERTY} (${bulkhead.wachtbudgetMs()}) moet minstens " +
                "berichtensessiecache.magazijn-query-timeout-seconds ($magazijnQueryTimeoutSeconds) seconden zijn. " +
                "Het wachtbudget kan niet hoger dan ${MagazijnAggregatieBulkhead.MAX_WACHTTIJD_MS_PLAFOND} ms, dus " +
                "verlaag bij een hogere query-timeout die timeout naar hooguit " +
                "${MagazijnAggregatieBulkhead.MAX_WACHTTIJD_MS_PLAFOND / MILLIS_PER_SECOND} seconden."
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

        // Eén lookup per magazijn, gedeeld door het GESTART- en het VOLTOOID-event: twee keer
        // ophalen zou de weergavenaam binnen dezelfde ronde uiteen kunnen laten lopen.
        val namen = clients.keys.associateWith { magazijnId -> clientFactory.getNaam(magazijnId) }

        // Alle GESTART-events vooruit, vóór de eerste bevraging. De ronde-wachtrij hieronder pakt
        // een organisatie pas op als er een plek vrij is; zonder deze vooruitgeschoven events zou
        // een wachtende organisatie helemáál niet in de stroom voorkomen — onzichtbaar in plaats
        // van "wordt nog opgehaald". Het portaal krijgt zo direct de volledige lijst.
        val gestartEvents: Multi<MagazijnEvent> = Multi.createFrom().iterable(
            clients.keys.map { magazijnId -> MagazijnBevragingGestart(magazijnId, namen[magazijnId]) },
        )

        // Diagnostiek van de wachtrij: wanneer een organisatie aan de beurt kwam, en of ze
        // uiteindelijk allemaal bevraagd zijn. Zonder deze twee getallen is de wachtrij onzichtbaar
        // — de organisaties die nog in de rij staan zijn per definitie stil.
        val rondeStartNanos = System.nanoTime()
        val aanDeBeurt = AtomicInteger(0)

        val voltooidStreams = clients.map { (magazijnId, client) ->
            bouwVoltooidStream(magazijnId, namen[magazijnId], client, ontvangerString, alleBerichten, geslaagd, mislukt)
                // `onSubscription` vuurt precies wanneer de ronde-wachtrij deze organisatie oppakt.
                // Bij een fan-out boven de per-ronde-grens zie je hier de golven: de eerste groep op
                // ~0 ms, de rest zodra er een plek vrijkomt.
                .onSubscription().invoke(
                    Runnable {
                        log.debugf(
                            "Magazijn %s aan de beurt (%d van %d, %d ms na de start van de ronde)",
                            magazijnId, aanDeBeurt.incrementAndGet(), clients.size, verstrekenMs(rondeStartNanos),
                        )
                    },
                )
        }

        // Aggregatie-pipeline: draait onafhankelijk van de SSE-client door tot voltooiing.
        return Multi.createBy().concatenating().streams(
            gestartEvents,
            bulkhead.ronde(voltooidStreams).onCompletion().invoke(
                Runnable { logRondeAfgerond(clients.size, rondeStartNanos, geslaagd, mislukt) },
            ),
            aggregeerEnSlaOp(cacheKey, clients.size, alleBerichten, geslaagd, mislukt),
        )
    }

    /**
     * Sluit de ronde af in de log: hoeveel organisaties er bevraagd zijn van hoeveel er waren, en
     * hoe lang dat duurde. Op INFO omdat dit het antwoord is op "zijn ze allemaal aan bod gekomen"
     * — één regel per ophaalronde, en zonder PII (aantallen en een duur, geen ontvanger).
     */
    private fun logRondeAfgerond(totaal: Int, rondeStartNanos: Long, geslaagd: AtomicInteger, mislukt: AtomicInteger) {
        val bevraagd = geslaagd.get() + mislukt.get()

        log.infof(
            "Ophaalronde afgerond in %d ms: %d van %d organisaties bevraagd (%d geslaagd, %d niet)",
            verstrekenMs(rondeStartNanos), bevraagd, totaal, geslaagd.get(), mislukt.get(),
        )
    }

    private fun verstrekenMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / NANOS_PER_MILLI

    /**
     * Atomaire lock via trySetAggregationStatus (SET NX EX in één commando): voorkom
     * concurrent ophalen voor dezelfde ontvanger. Blokkerende await() is hier bewust:
     * het aanroepende SSE-endpoint van de consumer (OphalenSseResource) is @Blocking gemarkeerd. De
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
                // Flag wissen vóór de cleanup en daarna herstellen; zie de toelichting op het
                // resolver-pad. Blijft hij staan, dan breekt de await in de cleanup meteen af en
                // meldt het log ten onrechte dat de lock op zijn TTL leunt.
                Thread.interrupted()
                log.warnf(ex, "(errorId=%s) Lock-acquire onderbroken voor key=%s", errorId, cacheKey)
                cleanupLockMetFoutStatus(cacheKey, "lock-acquire interrupted", errorId, afbreking = true)
                Thread.currentThread().interrupt()
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

            // Single source `ref` voorkomt format-drift tussen het veld en de tekst.
            val ref = ex.errorId.toString()

            ResolveUitkomst.FoutStream(
                Multi.createFrom().item(
                    OphalenMisluktVoorBevraging(
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
        val chain = ex.causeChain()
        // Uitsluitend de fout zelf, om dezelfde reden als in isAfbreking: de interrupt-flag is
        // thread-sticky en zou een storing op een hergebruikte worker-thread als onderbreking
        // laten gelden — en dat oordeel stuurt hieronder de alert-onderdrukking aan.
        val wasInterrupted = chain.hasCauseOf(InterruptedException::class.java)
        val isOuterTimeout = chain.bevatTimeout()

        when {
            wasInterrupted -> {
                // Flag wissen vóór de cleanup en daarna herstellen: staat hij nog, dan gooit de
                // await in de cleanup onmiddellijk en wordt de lock niet vrijgegeven terwijl het
                // log meldt dat hij op de TTL leunt. Mutiny heeft de flag al gezet toen de await
                // afbrak, dus dit herstelt hem, het zet hem niet voor het eerst.
                Thread.interrupted()
                val errorId = UUID.randomUUID()

                log.warnf(ex, "(errorId=%s) Resolver-await onderbroken voor key=%s", errorId, cacheKey)
                cleanupLockMetFoutStatus(cacheKey, "resolver-await interrupted", errorId, afbreking = true)
                Thread.currentThread().interrupt()
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
            // SSE-stream is al actief op dit punt; een fout-event geeft de client
            // dezelfde UX als het aggregatie-faalpad i.p.v. een mid-stream HTTP-500.
            return Multi.createFrom().item(
                OphalenMisluktVoorBevraging(
                    foutmelding = "Interne fout bij opslaan resultaten (ref: $ref)",
                    referentie = ref,
                ),
            )
        }

        return Multi.createFrom().item(
            OphalenGereed(
                totaalBerichten = 0,
                geslaagd = 0,
                mislukt = 0,
                totaalMagazijnen = 0,
            ),
        )
    }

    /**
     * Bouwt de event-stream voor één magazijn: het VOLTOOID-event
     * (OK/TIMEOUT/FOUT/NIET_OPGEHAALD). Het bijbehorende GESTART-event is al vooruitgeschoven in
     * [haalBerichtenOp], omdat de merge deze stream pas subscribet als het magazijn aan de beurt
     * is. De per-magazijn query-timeout levert het primaire TIMEOUT-signaal; de berichten-cap
     * beschermt de heap tegen een rogue magazijn; de fout-classificatie
     * ([classifyMagazijnFault]) bepaalt log-niveau + eindgebruiker-melding. Geslaagde berichten
     * worden in [alleBerichten] verzameld; [geslaagd]/[mislukt] tellen de uitkomst voor de
     * eind-aggregatie.
     */
    private fun bouwVoltooidStream(
        magazijnId: String,
        naam: String?,
        client: MagazijnClient,
        ontvangerString: String,
        alleBerichten: MutableList<Bericht>,
        geslaagd: AtomicInteger,
        mislukt: AtomicInteger,
    ): Multi<MagazijnEvent> {
        // De half-open probe die `toegestaan()` claimt wordt door niets anders gewist dan een
        // terminale melding: raakt die zoek, dan blijft dit magazijn tot de herstart overgeslagen
        // met een CIRCUIT_OPEN die niets over dát magazijn zegt. De vlag maakt de melding
        // idempotent, zodat het normale pad hem doet en de terminatie-haak hieronder alleen
        // inspringt wanneer de bevraging afbrak vóór een uitkomst.
        val circuitGemeld = AtomicBoolean(false)

        // `deferred` zodat de circuit-check PAS bij subscription loopt: een nooit-gesubscribete
        // stream (cancel vóór subscribe in het SSE-pad) claimt zo geen half-open probe. Het bulkhead
        // beheert zijn eigen acquire/release-pairing binnen [MagazijnAggregatieBulkhead.begrensd].
        val resultUni: Uni<MagazijnResult> = Uni.createFrom().deferred {
            if (!circuitBreaker.toegestaan(magazijnId)) {
                // Circuit open na herhaalde storingen: call overslaan en direct een nette
                // CIRCUIT_OPEN-failure leveren — geen thread bezet, geen wachttijd tot de
                // timeout. De gebruiker krijgt zo snel "tijdelijk niet beschikbaar". toegestaan()
                // gaf false, dus er is GEEN half-open probe geclaimd: geen registreerCircuit nodig.
                log.debugf("Magazijn %s (%s) overgeslagen: circuit open", magazijnId, naam)

                Uni.createFrom().item(
                    MagazijnResult.Failure(magazijnId, naam, MagazijnCircuitOpenException(magazijnId), MagazijnFault.CIRCUIT_OPEN),
                )
            } else {
                bulkhead.begrensd(
                    label = "Magazijn $magazijnId",
                    verlopen = {
                        // Wachtbudget verstreken zonder permit: call niet gestart, dus OVERBELAST.
                        // toegestaan() kan nét een half-open probe hebben geclaimd; die MOET via
                        // registreerCircuit (→ MELD_ONBESLIST) worden vrijgegeven, anders blijft het
                        // circuit open.
                        val result = MagazijnResult.Failure(
                            magazijnId, naam, MagazijnOverbelastException(magazijnId), MagazijnFault.OVERBELAST,
                        )

                        logMagazijnFault(result.error, magazijnId, naam, MagazijnFault.OVERBELAST)
                        registreerCircuit(magazijnId, result, circuitGemeld)

                        Uni.createFrom().item(result)
                    },
                    taak = {
                        // De blokkerende magazijn-call draait op de context-bewuste default-worker-
                        // pool (de downstream-Redis-writes vereisen de Vert.x-duplicated-context, die
                        // een eigen pool niet levert); het bulkhead begrenst enkel de gelijktijdigheid.
                        Uni.createFrom()
                            .item { paginaLezer.leesAlleBerichten(client, magazijnId, ontvangerString, magazijnQueryBudget) }
                            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                            .ifNoItem().after(Duration.ofSeconds(magazijnQueryTimeoutSeconds)).fail()
                            .map<MagazijnResult> { oogst -> naarMagazijnResult(oogst, magazijnId, naam) }
                            .onFailure(Exception::class.java).recoverWithItem { error ->
                                val fault = classifyMagazijnFault(error)

                                logMagazijnFault(error, magazijnId, naam, fault)
                                MagazijnResult.Failure(magazijnId, naam, error, fault)
                            }
                            .onItem().invoke { result -> registreerCircuit(magazijnId, result, circuitGemeld) }
                    },
                )
            }
        }

        return resultUni
            // Vangnet rond álles wat buiten de recover van de taak valt: het inplannen van een
            // wachtstap (scheduler weg bij shutdown), de verlopen-tak, en de circuit-registratie
            // erna. Zonder dit vangnet faalt de substream, annuleert de merge zijn siblings en valt
            // de hele ophaalronde om: geen slotevent, geen opslag, en de status blijft BEZIG tot de
            // lock-TTL — voor de gebruiker minutenlang 409 op elke nieuwe poging. OVERBELAST is
            // hier de eerlijke uitkomst: het magazijn is niet bevraagd, dus geen uitspraak erover,
            // en een half-open probe komt via MELD_ONBESLIST terug.
            .onFailure().recoverWithItem { fout ->
                log.errorf(
                    fout, "Bevraging van magazijn %s (%s) brak af vóór een uitkomst (cause=%s)",
                    magazijnId, naam, fout.javaClass.simpleName,
                )

                MagazijnResult.Failure(magazijnId, naam, MagazijnOverbelastException(magazijnId), MagazijnFault.OVERBELAST)
            }
            .onTermination().invoke(Runnable { meldOnbeslistIndienNodig(magazijnId, circuitGemeld) })
            .toMulti()
            .map { result -> naarVoltooidEvent(result, alleBerichten, geslaagd, mislukt) }
    }

    /**
     * Geeft een eventueel geclaimde half-open probe vrij wanneer de bevraging termineerde zonder
     * uitkomst (annulering). Bij een normale afronding heeft [registreerCircuit] de vlag al gezet
     * en doet dit niets.
     */
    private fun meldOnbeslistIndienNodig(magazijnId: String, circuitGemeld: AtomicBoolean) {
        if (!circuitGemeld.compareAndSet(false, true)) return

        log.debugf("Bevraging magazijn %s afgebroken vóór een uitkomst; half-open probe vrijgegeven", magazijnId)
        meldCircuitVeilig(magazijnId) { circuitBreaker.meldOnbeslist(magazijnId) }
    }

    private fun naarMagazijnResult(oogst: GepagineerdeBerichten, magazijnId: String, naam: String?): MagazijnResult {
        // Magazijn levert MagazijnBericht-DTO's; vlak af naar het cache-domein (toBericht) en
        // valideer defensief (BerichtLimieten). Eén invalid bericht mag de batch niet killen — drop
        // het stuk en log warn i.p.v. de hele magazijn-bevraging te laten falen. Dit geldt óók voor
        // een ongeldige ontvanger-identificatie: toBericht bouwt het gevalideerde domeintype en kan
        // gooien (onbekend type, elfproef/lengte), dus vangen we dat hier per bericht.
        val berichten = oogst.berichten
            .mapNotNull { magazijnBericht -> naarValidCacheBericht(magazijnBericht, magazijnId) }

        // Een gedropt bericht is ook post die de ontvanger niet krijgt; zonder deze term meldt het
        // event "8 berichten, niets afgekapt, 10 beschikbaar".
        val afgekapt = oogst.afgekapt || berichten.size < oogst.berichten.size

        if (afgekapt) {
            // Warn, geen error: meestal een grens die werkt. De oorzaak blijft uit de tekst —
            // afkappen gebeurt ook zonder dat de cap geraakt is, en "verhoog de cap" wijst dan mis.
            log.warnf(
                "Magazijn %s (%s) leverde minder dan er beschikbaar is: %d opgehaald van %s",
                magazijnId, naam, berichten.size, oogst.totaalBeschikbaar?.toString() ?: "onbekend",
            )
        }

        return MagazijnResult.Success(magazijnId, naam, berichten, afgekapt, oogst.totaalBeschikbaar)
    }

    /**
     * Mapt één magazijn-DTO naar een gevalideerd cache-[Bericht], of null wanneer het bericht
     * moet worden overgeslagen. Twee drop-redenen, beide log-warn-en-drop (één pathologisch
     * bericht mag de aggregatie niet kapotmaken): een ongeldige ontvanger-identificatie
     * (toBericht gooit) of een limietsoverschrijding ([BerichtValidator]). berichtId/magazijnId
     * zijn geen PII; de ontvanger-waarde wordt bewust niet gelogd.
     */
    private fun naarValidCacheBericht(magazijnBericht: MagazijnBericht, magazijnId: String): Bericht? {
        val bericht = try {
            magazijnBericht.toBericht(magazijnId)
        } catch (e: IllegalArgumentException) {
            log.warnf(
                "Bericht overgeslagen tijdens magazijn-aggregatie (ongeldige ontvanger): berichtId=%s magazijnId=%s reden=%s",
                magazijnBericht.berichtId,
                magazijnId,
                e.message,
            )

            return null
        }

        return berichtValidator.valideerOrLogAndDrop(bericht)
    }

    /** Verwerkt het per-magazijn-resultaat in de tellers en mapt het naar het VOLTOOID-event. */
    private fun naarVoltooidEvent(
        result: MagazijnResult,
        alleBerichten: MutableList<Bericht>,
        geslaagd: AtomicInteger,
        mislukt: AtomicInteger,
    ): MagazijnBevragingVoltooid = when (result) {
        is MagazijnResult.Success -> {
            alleBerichten.addAll(result.berichten)
            geslaagd.incrementAndGet()
            MagazijnBevragingGeslaagd(
                magazijnId = result.magazijnId,
                naam = result.naam,
                aantalBerichten = result.berichten.size,
                afgekapt = result.afgekapt,
                totaalBeschikbaar = result.totaalBeschikbaar,
            )
        }
        is MagazijnResult.Failure -> {
            mislukt.incrementAndGet()
            MagazijnBevragingMislukt(
                magazijnId = result.magazijnId,
                naam = result.naam,
                fout = magazijnFoutStatusVoor(result.fault),
                foutmelding = foutmeldingVoor(result.fault),
            )
        }
    }

    /**
     * Voedt de per-magazijn circuit breaker met de uitkomst van een echte aggregatie-call. Elke
     * afgeronde call geeft een terminale actie ([circuitActieVoor]) zodat een half-open probe
     * nooit blijft hangen — ook niet bij een niet-storing-fout (4xx/malformed) of een bevraging die
     * door een vol bulkhead niet gestart is (OVERBELAST).
     */
    private fun registreerCircuit(magazijnId: String, result: MagazijnResult, circuitGemeld: AtomicBoolean) {
        circuitGemeld.set(true)

        meldCircuitVeilig(magazijnId) {
            when (circuitActieVoor(result)) {
                CircuitActie.MELD_SUCCES -> circuitBreaker.meldSucces(magazijnId)
                CircuitActie.MELD_FOUT -> circuitBreaker.meldFout(magazijnId)
                CircuitActie.MELD_ONBESLIST -> circuitBreaker.meldOnbeslist(magazijnId)
            }
        }
    }

    /**
     * Voert een circuit-melding uit zonder dat een fout daarin de bevraging kan wegnemen. De
     * uitkomst van de bevraging is op dit punt al bepaald; een mislukte boekhouding kost hooguit
     * één probe-venster, terwijl een doorgegeven fout de hele ophaalronde meesleurt. Wel `errorf`:
     * hier falen is per definitie een eigen bug, geen upstream-conditie.
     */
    private fun meldCircuitVeilig(magazijnId: String, melding: () -> Unit) {
        try {
            melding()
        } catch (bug: RuntimeException) {
            log.errorf(bug, "Circuit-registratie mislukt voor magazijn %s; de uitkomst van de bevraging blijft geldig", magazijnId)
        }
    }

    /**
     * Vertaalt een fault naar de status die de gebruiker op de lijn ziet. Twee faults verdienen een
     * eigen woord omdat de gebruiker er iets anders mee kan: een echte timeout (opnieuw proberen is
     * zinnig) en een bevraging die door onze eigen gelijktijdigheidsgrens niet eens gestart is
     * (NIET_OPGEHAALD — geen uitspraak over dat magazijn). De overige faults zijn
     * ononderscheidbaar "mislukt" (BIO 14.1.3 — geen technisch onderscheid richting
     * eindgebruiker).
     */
    private fun magazijnFoutStatusVoor(fault: MagazijnFault): MagazijnFoutStatus = when (fault) {
        MagazijnFault.TIMEOUT -> MagazijnFoutStatus.TIMEOUT
        MagazijnFault.OVERBELAST -> MagazijnFoutStatus.NIET_OPGEHAALD
        MagazijnFault.MALFORMED, MagazijnFault.OVERFLOW, MagazijnFault.HTTP_5XX,
        MagazijnFault.HTTP_4XX, MagazijnFault.HTTP_3XX, MagazijnFault.NETWORK,
        MagazijnFault.INTERNAL_BUG, MagazijnFault.CIRCUIT_OPEN,
        -> MagazijnFoutStatus.FOUT
    }

    internal fun foutmeldingVoor(fault: MagazijnFault): String = when (fault) {
        MagazijnFault.TIMEOUT -> "Magazijn reageerde niet binnen de timeout"
        MagazijnFault.MALFORMED -> "Magazijn leverde onleesbare respons (mogelijk schema-drift, contact beheerder)"
        MagazijnFault.OVERFLOW -> "Magazijn leverde een onbruikbare berichtenlijst (paginering genegeerd, contact beheerder)"
        MagazijnFault.HTTP_5XX -> "Magazijn tijdelijk niet bereikbaar"
        MagazijnFault.HTTP_4XX -> "Magazijn heeft de aanvraag geweigerd (configuratiefout, contact beheerder)"
        MagazijnFault.HTTP_3XX -> "Magazijn verwijst door naar een ander adres (configuratiefout, contact beheerder)"
        MagazijnFault.CIRCUIT_OPEN -> "Magazijn tijdelijk niet beschikbaar (herhaalde storingen)"
        MagazijnFault.OVERBELAST -> "Nog niet opgehaald: te veel organisaties tegelijk in behandeling (probeer het opnieuw)"
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
                    .map<MagazijnEvent> { _ ->
                        OphalenGereed(
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
     * specifieke sessie én naar het OphalenFout.referentie-veld.
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
        val afgebroken = isAfbreking(error)

        // Een afbreking is geen storing. De aggregatie loopt bewust door na een client-disconnect,
        // dus de reële trigger hier is een pod die uitgaat. Op errorf-niveau loggen zou beheer
        // wakker maken voor normaal gedrag en echte fouten laten ondersneeuwen.
        if (afgebroken) {
            log.infof(
                "(errorId=%s) Opslaan na aggregatie afgebroken (key=%s, berichten=%d); geen storing",
                errorId, cacheKey, alleBerichten.size,
            )
        } else {
            log.errorf(
                error,
                "(errorId=%s) Fout bij opslaan in cache na aggregatie (key=%s, berichten=%d, geslaagd=%d, mislukt=%d)",
                errorId, cacheKey, alleBerichten.size, geslaagd.get(), mislukt.get(),
            )
        }

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
            // Een afbreking krijgt die marker niet: beheer oppiepen voor een gesloten
            // pagina of een herstart laat echte storingen ondersneeuwen.
            // Bewust alleen `e`: is de eerste fout een afbreking maar de tweede een echte
            // Redis-uitval, dan is de cache voor deze sessie wél onbruikbaar en blijft de lock
            // op de TTL hangen — precies de conditie waarvoor de marker bestaat.
            .onFailure().invoke { e ->
                if (isAfbreking(e)) {
                    log.infof(
                        "(errorId=%s) Ook de fout-status kon niet worden weggeschreven na een afbreking (key=%s); lock leunt op TTL",
                        errorId, cacheKey,
                    )
                } else {
                    log.fatalf(
                        e,
                        "[ALERT cache_doublefail] (errorId=%s) Cache-write FAIL/FAIL (key=%s, berichten=%d, geslaagd=%d, mislukt=%d): Redis onbruikbaar voor sessie, lock leunt op TTL",
                        errorId, cacheKey, alleBerichten.size, geslaagd.get(), mislukt.get(),
                    )
                }
            }
            .onFailure().recoverWithNull()
            // Meld expliciet dat de eerder per-magazijn getoonde resultaten niet
            // bewaard zijn: de VOLTOOID-events zijn al geëmit, maar de cache-write
            // faalde, dus de client moet opnieuw ophalen i.p.v. te denken dat de
            // resultaten beschikbaar zijn via GET /berichten.
            .replaceWith(
                OphalenMisluktNaBevraging(
                    foutmelding = "Resultaten konden niet worden opgeslagen; haal opnieuw op (ref: $ref)",
                    geslaagd = geslaagd.get(),
                    mislukt = mislukt.get(),
                    totaalMagazijnen = totaalMagazijnen,
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
        // De aanroeper weet of dit een afbrekingspad is; hier afleiden uit de opgevangen fout kan
        // niet. Een gezette interrupt-flag laat de await hierónder onmiddellijk falen met een
        // InterruptedException, óók wanneer de werkelijke oorzaak een echte storing was — dan zou
        // de alert-marker juist bij een storing wegvallen.
        afbreking: Boolean = false,
    ) {
        try {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                AggregationStatus(status = OphalenStatus.FOUT),
            ).await().atMost(Duration.ofSeconds(cacheAwaitTimeoutSeconds))

            log.warnf("Lock vrijgegeven na fout (errorId=%s) voor key=%s: %s", errorId, cacheKey, oorzaak)
        } catch (cleanupEx: Exception) {
            // Een afbreking is ook hier geen storing: een rolling restart tijdens een lopende
            // ophaalstart zou anders gegarandeerd een FATAL met alert-marker per onderbroken
            // sessie opleveren.
            if (afbreking) {
                log.infof(
                    "Lock-cleanup afgebroken (errorId=%s) voor key=%s: %s — lock leunt op TTL",
                    errorId,
                    cacheKey,
                    oorzaak,
                )

                return
            }

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

    /** Beide timeout-typen: die van `ifNoItem`, en die waarmee de pagineerlus zichzelf afbreekt. */
    private fun List<Throwable>.bevatTimeout(): Boolean =
        hasCauseOf(io.smallrye.mutiny.TimeoutException::class.java) ||
            hasCauseOf(java.util.concurrent.TimeoutException::class.java)

    private companion object {
        private const val MAX_CAUSE_DEPTH = 32
        const val MILLIS_PER_SECOND = 1000L
        const val NANOS_PER_MILLI = 1_000_000L
    }

    internal enum class LockAcquireError { JSON_SERIALIZATION, TIMEOUT, IO_FAULT, INTERRUPTED, UNEXPECTED }

    // Internal voor directe unit-test van classificatie + cycle-/depth-cap-gedrag.
    internal fun classifyLockAcquireError(ex: Throwable): LockAcquireError {
        val chain = ex.causeChain()

        return when {
            chain.hasCauseOf(InterruptedException::class.java) -> LockAcquireError.INTERRUPTED
            chain.hasCauseOf(JsonProcessingException::class.java) -> LockAcquireError.JSON_SERIALIZATION
            chain.bevatTimeout() -> LockAcquireError.TIMEOUT
            chain.hasCauseOf(java.io.IOException::class.java) -> LockAcquireError.IO_FAULT
            else -> LockAcquireError.UNEXPECTED
        }
    }

    /**
     * Onderscheidt "het ophalen is afgebroken" van "er ging iets stuk". Bij een pod-herstart
     * midden in de aggregatie levert dat een annulering of een interrupt op; dat is normaal
     * gedrag en hoort geen storingsmelding op te leveren. [classifyMagazijnFault] herkent
     * annulering ook, maar mapt hem op `NETWORK` — dat telt daar wél als storing voor de circuit
     * breaker. Die keuze staat los van deze: hier gaat het om het log-niveau, daar om de vraag of
     * een magazijn tijdelijk overgeslagen moet worden.
     *
     * Uitsluitend de meegegeven fout telt, niet de interrupt-flag van de huidige thread. Die
     * flag is thread-sticky en wordt elders in deze klasse bewust gezet zonder hem ooit te
     * wissen; komt zo'n worker-thread terug in de pool, dan zou een échte Redis-uitval daarna
     * als afbreking gelden en zou de alert stilvallen.
     */
    internal fun isAfbreking(error: Throwable): Boolean {
        val chain = error.causeChain()

        return chain.hasCauseOf(java.util.concurrent.CancellationException::class.java) ||
            chain.hasCauseOf(InterruptedException::class.java)
    }

    // Internal voor directe unit-test van de classificatie-tabel (zie service-test).
    internal fun classifyMagazijnFault(error: Throwable): MagazijnFault {
        // Cause-walking eenmalig via causeChain(); meerdere instanceof-checks tegen
        // dezelfde list — voorkomt log-storm van depth-cap warnf bij elke check.
        val chain = error.causeChain()
        val webEx = chain.findCauseOf<WebApplicationException>()

        return when {
            chain.hasCauseOf(MagazijnResponseOverflow::class.java) -> MagazijnFault.OVERFLOW
            chain.bevatTimeout() -> MagazijnFault.TIMEOUT
            chain.hasCauseOf(JsonProcessingException::class.java) -> MagazijnFault.MALFORMED
            chain.hasCauseOf(ConnectException::class.java) -> MagazijnFault.NETWORK
            webEx != null -> {
                val status = webEx.response?.status ?: 0

                when {
                    status in 500..599 -> MagazijnFault.HTTP_5XX
                    status >= 400 -> MagazijnFault.HTTP_4XX
                    // Een 3xx die als fout terugkomt betekent dat de client de doorverwijzing
                    // niet volgde: het magazijn staat op een ander adres dan wij kennen. Een
                    // eigen fault i.p.v. HTTP_4XX, zodat het log de werkelijke oorzaak noemt —
                    // een beheerder die dit ziet moet naar de magazijn-URL kijken, niet naar
                    // een 4xx die er niet is.
                    //
                    // LET OP: de REST-client maakt vandaag pas een WebApplicationException vanaf
                    // status 400, dus via het echte magazijn-pad komt een 301 hier niet aan — die
                    // strandt eerder op het parsen van de redirect-body en wordt MALFORMED. Deze
                    // tak is de vangnet-kant van de classificatie; hem sluitend maken vraagt een
                    // eigen ResponseExceptionMapper op MagazijnClient. TODO(MinBZK/MijnOverheidZakelijk#870)
                    status in 300..399 -> MagazijnFault.HTTP_3XX
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
            MagazijnFault.OVERFLOW ->
                // ErrorF voor alert-routing: een contractbreuk die een beheerder moet zien.
                log.errorf(error, "Magazijn %s (%s) leverde een pagina groter dan gevraagd", magazijnId, naam)
            MagazijnFault.HTTP_5XX ->
                log.warnf(error, "Magazijn %s (%s) 5xx", magazijnId, naam)
            MagazijnFault.HTTP_4XX ->
                log.errorf(error, "Magazijn %s (%s) 4xx — configuratie/auth-fout", magazijnId, naam)
            // Eigen tak, niet samengevoegd met 4xx: wie dit ziet moet de magazijn-URL in het
            // register nakijken, niet naar rechten of een verkeerd pad zoeken.
            MagazijnFault.HTTP_3XX ->
                log.errorf(error, "Magazijn %s (%s) verwijst door — geconfigureerde URL wijst niet naar het magazijn", magazijnId, naam)
            MagazijnFault.NETWORK ->
                log.warnf(error, "Magazijn %s (%s) niet bereikbaar (network/processing)", magazijnId, naam)
            // Bewust overgeslagen door de circuit breaker — geen echte call-fout. Het CIRCUIT_OPEN-
            // pad logt zelf op debug; deze tak is een vangnet mocht een CIRCUIT_OPEN onverwacht
            // via classifyMagazijnFault binnenkomen (kan niet, maar houdt de when exhaustief).
            MagazijnFault.CIRCUIT_OPEN ->
                log.debugf("Magazijn %s (%s) overgeslagen door circuit breaker", magazijnId, naam)
            // Bulkhead-saturatie: warnf (capaciteits-/load-signaal voor ops), geen errorf — het is
            // geen eigen-bug maar een overload-conditie.
            MagazijnFault.OVERBELAST ->
                log.warnf(error, "Aggregatie-bulkhead bleef vol; magazijn %s (%s) niet bevraagd (OVERBELAST)", magazijnId, naam)
            MagazijnFault.INTERNAL_BUG ->
                log.errorf(error, "Onverwachte fout bij magazijn %s (%s) (cause=%s)", magazijnId, naam, error.javaClass.simpleName)
        }
    }
}
