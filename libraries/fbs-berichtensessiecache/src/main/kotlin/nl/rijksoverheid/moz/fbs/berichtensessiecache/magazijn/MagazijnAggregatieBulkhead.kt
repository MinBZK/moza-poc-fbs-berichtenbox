package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

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
 * pool levert die; een eigen pool niet (en `ThreadContext` propageert de Vert.x-duplicated-context
 * niet betrouwbaar) — dan hangen de Redis-writes. Daarom blijven de calls op de default-pool en
 * begrenst deze semafoor enkel hun GELIJKTIJDIGHEID ([maxConcurrent]): hooguit zoveel default-pool-
 * threads zijn met magazijn-calls bezig, de rest blijft vrij voor andere endpoints. Een vol bulkhead
 * wijst direct af ([probeerBinnen] = false → [MagazijnFault.OVERBELAST]: snelle "tijdelijk niet
 * beschikbaar" zonder een thread te bezetten of te bufferen).
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

    // Fair=true: first-come-first-served, voorkomt uithongering van een wachtende call onder druk.
    private val semaphore = Semaphore(maxConcurrent, true)

    /** Probeer een permit te claimen. `true` = call mag door; `false` = bulkhead vol, snel afwijzen. */
    fun probeerBinnen(): Boolean = semaphore.tryAcquire()

    /** Geef een eerder geclaimde permit vrij. MOET precies één keer per geslaagde [probeerBinnen]. */
    fun verlaat() = semaphore.release()

    /** Aantal vrije permits — alleen voor diagnostiek/tests. */
    internal fun vrijePermits(): Int = semaphore.availablePermits()
}
