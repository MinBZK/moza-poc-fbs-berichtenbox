package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration
import java.util.concurrent.Semaphore

/**
 * Semafoor-bulkhead die het AANTAL gelijktijdige blokkerende magazijn-aggregatie-calls begrenst.
 *
 * De gegenereerde [MagazijnClient] is synchroon (`jaxrs-spec`); elke call houdt een worker-thread
 * vast tot het antwoord of de read-timeout. Zonder begrenzing kan één trage leverancier de
 * gedeelde Quarkus default-worker-pool laten vollopen en zo ook niet-gerelateerde endpoints
 * (GET/POST /berichten) blokkeren.
 *
 * Waarom een semafoor en GEEN eigen thread-pool: de aggregatie schrijft downstream naar Redis
 * (reactieve client), wat de Vert.x-duplicated-context vereist. De context-bewuste default-worker-
 * pool levert die; een eigen pool niet (en thread-context-propagatie levert de Vert.x-duplicated-
 * context niet betrouwbaar) — dan hangen de Redis-writes. Daarom blijven de calls op de default-pool
 * en begrenst deze semafoor enkel hun GELIJKTIJDIGHEID ([maxConcurrent]): hooguit zoveel default-
 * pool-threads zijn met magazijn-calls bezig, de rest blijft vrij voor andere endpoints.
 *
 * **De grens is een wachtrij, geen zeef.** Is er geen vrije permit, dan wacht de bevraging tot
 * [maxWachttijdMs] — asynchroon, dus zónder een worker-thread te bezetten — en pas als dat budget
 * verstrijkt levert ze de niet-gelukt-tak. Zo blijft het thread-plafond exact [maxConcurrent],
 * hoeveel wachtenden er ook zijn, terwijl een ondernemer met meer organisaties dan de grens ze
 * alsnog allemaal bevraagd krijgt. Vóór deze wachtrij was de enige acquire `tryAcquire()` en
 * kregen de organisaties boven de grens direct een afwijzing die niets over hén zei.
 *
 * Het wachten polt in plaats van permits aan een wachtrij van emitters over te dragen. Bij
 * overdracht bestaat een race die lokaal niet te sluiten is: op het moment dat de release een
 * permit aan de kop-wachtende toekent, kan die net zijn wachtbudget hebben overschreden en
 * geannuleerd zijn — de completion is dan een no-op en de permit is van niemand meer. Bij pollen
 * houdt alleen een pijplijn die op dát moment leeft een permit vast, dus die klasse van lek
 * bestaat niet. Wat pollen niet biedt is exacte FIFO-eerlijkheid; dat hoeft hier ook niet, want de
 * volgorde binnen één ophaalronde wordt al door [maxParallelPerRonde] geborgd (zie hieronder) en
 * gelijktijdige sessies kunnen daardoor geen positie-afhankelijke, structurele achterstand oplopen.
 *
 * [maxParallelPerRonde] is de tweede laag: de aggregatie subscribet per ophaalronde op maximaal
 * zoveel bevragingen tegelijk en pakt de volgende pas als er één afgerond is. Die wachtrij kost
 * geen permit en geen thread — hij zit in de backpressure van de merge — en zorgt ervoor dat een
 * ronde niet in één keer meer permits opvraagt dan er zijn.
 *
 * De acquire/release-pairing zit volledig in [begrensd] (geen losse claim/vrijgave-API): de permit
 * wordt tijdens de subscription geclaimd en op élke terminatie (succes/fout/cancel) — óók als het
 * opbouwen van de taak-`Uni` gooit — precies één keer vrijgegeven. Zo kan een caller de permits niet
 * onbalanceren (lek of dubbele release die de semafoor stilletjes boven [maxConcurrent] oprekt).
 *
 * Bekende beperking (bewuste trade-off, geen per-magazijn bulkhead): de permits zijn gedeeld over
 * álle magazijnen/ontvangers. Tijdens het venster waarin een trage leverancier de permits opsoupeert
 * vóórdat zijn [MagazijnCircuitBreaker] opent, moeten ook calls naar gezonde leveranciers wachten;
 * zodra het circuit opent worden diens calls overgeslagen en herstelt het bulkhead.
 */
@ApplicationScoped
internal class MagazijnAggregatieBulkhead(
    @param:ConfigProperty(name = MAX_CONCURRENT_PROPERTY, defaultValue = MAX_CONCURRENT_DEFAULT)
    private val maxConcurrent: Int,
    @param:ConfigProperty(name = MAX_PARALLEL_PER_RONDE_PROPERTY, defaultValue = MAX_PARALLEL_PER_RONDE_DEFAULT)
    val maxParallelPerRonde: Int,
    @param:ConfigProperty(name = MAX_WACHTTIJD_MS_PROPERTY, defaultValue = MAX_WACHTTIJD_MS_DEFAULT)
    private val maxWachttijdMs: Long,
) {
    // Fail-fast in het init-blok (vóór de semafoor-constructie): een niet-positieve waarde maakt
    // `Semaphore` met 0/negatieve permits — altijd vol → elke bevraging wacht zijn budget vol en
    // levert daarna niets. `Semaphore` zelf gooit niet bij ≤0, dus de check moet hier, niet pas in
    // een @PostConstruct ná het veld.
    init {
        require(maxConcurrent > 0) {
            "$MAX_CONCURRENT_PROPERTY ($maxConcurrent) moet groter zijn dan 0"
        }

        require(maxParallelPerRonde > 0) {
            "$MAX_PARALLEL_PER_RONDE_PROPERTY ($maxParallelPerRonde) moet groter zijn dan 0"
        }

        // Een ronde die meer bevragingen tegelijk aanbiedt dan er permits zijn, laat het overschot
        // structureel zijn wachtbudget verbranden in plaats van te wachten op een permit die zo
        // vrijkomt. De per-ronde-laag hoort binnen de globale grens te blijven.
        require(maxParallelPerRonde <= maxConcurrent) {
            "$MAX_PARALLEL_PER_RONDE_PROPERTY ($maxParallelPerRonde) mag niet groter zijn dan " +
                "$MAX_CONCURRENT_PROPERTY ($maxConcurrent)"
        }

        // 0 of lager betekent geen wachtrij: dan is dit weer de zeef die het was.
        require(maxWachttijdMs > 0) {
            "$MAX_WACHTTIJD_MS_PROPERTY ($maxWachttijdMs) moet groter zijn dan 0"
        }
    }

    // Geen fairness: de acquires zijn `tryAcquire()` (barge), en fairness geldt in `Semaphore`
    // alleen voor de blokkerende varianten — `fair=true` zou hier een no-op zijn. De eerlijkheid
    // die telt komt van maxParallelPerRonde: binnen een ronde krijgt élke organisatie zijn beurt.
    private val semaphore = Semaphore(maxConcurrent)

    /**
     * Voert [taak] uit onder één permit. Is het bulkhead vol, dan wacht de bevraging tot er een
     * permit vrijkomt en levert ze [verlopen] pas wanneer het wachtbudget verstreken is.
     *
     * De permit wordt geclaimd bij subscription (binnen `deferred`, zodat een nooit-gesubscribete/
     * geannuleerde stream niets claimt) en op élke terminatie van [taak] precies één keer
     * vrijgegeven — óók als het opbouwen van de taak-`Uni` synchroon gooit. Acquire en release zijn
     * zo een gesloten paar dat de caller niet kan onbalanceren.
     */
    fun <T> begrensd(verlopen: () -> Uni<T>, taak: () -> Uni<T>): Uni<T> =
        Uni.createFrom().deferred {
            verwerfPermit(deadlineNanos = System.nanoTime() + maxWachttijdMs * NANOS_PER_MILLI)
                .onItem().transformToUni { verkregen ->
                    if (verkregen) metPermit(taak) else verlopen()
                }
        }

    /**
     * Probeert een permit te claimen tot [deadlineNanos]: `true` = geclaimd (de caller moet hem
     * vrijgeven), `false` = budget verstreken. De wachtstap loopt op de scheduler van de
     * default-worker-pool en houdt géén thread bezet; de recursie bouwt geen stack op omdat elke
     * stap achter een asynchrone vertraging zit.
     */
    private fun verwerfPermit(deadlineNanos: Long): Uni<Boolean> =
        Uni.createFrom().deferred {
            when {
                semaphore.tryAcquire() -> Uni.createFrom().item(true)
                // Overflow-veilige vergelijking: verstreken zodra het verschil niet-negatief is.
                System.nanoTime() - deadlineNanos >= 0 -> Uni.createFrom().item(false)
                else -> Uni.createFrom().voidItem()
                    .onItem().delayIt().by(POLL_INTERVAL)
                    .onItem().transformToUni { _ -> verwerfPermit(deadlineNanos) }
            }
        }

    private fun <T> metPermit(taak: () -> Uni<T>): Uni<T> =
        try {
            taak().onTermination().invoke(Runnable { semaphore.release() })
        } catch (taakOpbouwFout: Throwable) {
            // Taak-Uni nooit opgebouwd → geen onTermination om de permit vrij te geven;
            // hier vrijgeven anders lekt deze permit permanent (geen reaper).
            semaphore.release()

            throw taakOpbouwFout
        }

    /** Aantal vrije permits — alleen voor diagnostiek/tests. */
    internal fun vrijePermits(): Int = semaphore.availablePermits()

    internal companion object {
        const val MAX_CONCURRENT_PROPERTY = "berichtensessiecache.magazijn-bulkhead.max-concurrent"
        const val MAX_CONCURRENT_DEFAULT = "40"
        const val MAX_PARALLEL_PER_RONDE_PROPERTY = "berichtensessiecache.magazijn-bulkhead.max-parallel-per-ronde"
        const val MAX_PARALLEL_PER_RONDE_DEFAULT = "20"
        const val MAX_WACHTTIJD_MS_PROPERTY = "berichtensessiecache.magazijn-bulkhead.max-wachttijd-ms"
        const val MAX_WACHTTIJD_MS_DEFAULT = "5000"

        private const val NANOS_PER_MILLI = 1_000_000L

        // Vast en niet configureerbaar: een magazijn-call duurt in de praktijk tientallen tot
        // honderden milliseconden, dus 25 ms voegt verwaarloosbaar veel wachttijd toe terwijl het
        // pollen zelf niets kost (een `tryAcquire` per wachtende per interval). Een knop erbij zou
        // een afweging suggereren die er niet is.
        private val POLL_INTERVAL: Duration = Duration.ofMillis(25)
    }
}
