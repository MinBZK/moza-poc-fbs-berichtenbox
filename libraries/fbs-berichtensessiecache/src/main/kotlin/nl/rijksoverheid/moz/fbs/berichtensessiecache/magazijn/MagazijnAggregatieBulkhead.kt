package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
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
 * De acquire/release-pairing zit volledig in [begrensd] (geen losse claim/vrijgave-API): de permit
 * wordt op subscription geclaimd en op élke terminatie (succes/fout/cancel) — óók als het opbouwen
 * van de taak-`Uni` gooit — precies één keer vrijgegeven. Zo kan een caller de permits niet
 * onbalanceren (lek of dubbele release die de semafoor stilletjes boven [maxConcurrent] oprekt).
 *
 * Bekende beperking (bewuste trade-off, geen per-magazijn bulkhead): de permits zijn gedeeld over
 * álle magazijnen/ontvangers. Tijdens het venster waarin een trage leverancier de permits opsoupeert
 * vóórdat zijn [MagazijnCircuitBreaker] opent, kunnen ook calls naar gezonde leveranciers worden
 * afgewezen; zodra het circuit opent worden diens calls overgeslagen en herstelt het bulkhead.
 */
@ApplicationScoped
internal class MagazijnAggregatieBulkhead(
    @param:ConfigProperty(name = "berichtensessiecache.magazijn-bulkhead.max-concurrent", defaultValue = "20")
    private val maxConcurrent: Int,
) {
    // Fail-fast in het init-blok (vóór de semafoor-constructie): een niet-positieve waarde maakt
    // `Semaphore` met 0/negatieve permits — altijd vol → stille, totale OVERBELAST. `Semaphore`
    // zelf gooit niet bij ≤0, dus de check moet hier, niet pas in een @PostConstruct ná het veld.
    init {
        require(maxConcurrent > 0) {
            "berichtensessiecache.magazijn-bulkhead.max-concurrent ($maxConcurrent) moet groter zijn dan 0"
        }
    }

    // Geen fairness: de enige acquire is `tryAcquire()` (barge), die nooit blokkeert/queue't — een
    // vol bulkhead wijst direct af. Er is dus geen wachtende call die starvation kan oplopen;
    // `fair=true` zou een no-op zijn.
    private val semaphore = Semaphore(maxConcurrent)

    /**
     * Voert [taak] uit onder één permit, of [afgewezen] als het bulkhead vol is. De permit wordt
     * geclaimd bij subscription (binnen `deferred`, zodat een nooit-gesubscribete/geannuleerde
     * stream niets claimt) en op élke terminatie van [taak] precies één keer vrijgegeven — óók als
     * het opbouwen van de taak-`Uni` synchroon gooit. Acquire en release zijn zo een gesloten paar
     * dat de caller niet kan onbalanceren.
     */
    fun <T> begrensd(afgewezen: () -> Uni<T>, taak: () -> Uni<T>): Uni<T> =
        Uni.createFrom().deferred {
            if (!semaphore.tryAcquire()) {
                afgewezen()
            } else {
                try {
                    taak().onTermination().invoke(Runnable { semaphore.release() })
                } catch (taakOpbouwFout: Throwable) {
                    // Taak-Uni nooit opgebouwd → geen onTermination om de permit vrij te geven;
                    // hier vrijgeven anders lekt deze permit permanent (geen reaper).
                    semaphore.release()

                    throw taakOpbouwFout
                }
            }
        }

    /** Aantal vrije permits — alleen voor diagnostiek/tests. */
    internal fun vrijePermits(): Int = semaphore.availablePermits()
}
