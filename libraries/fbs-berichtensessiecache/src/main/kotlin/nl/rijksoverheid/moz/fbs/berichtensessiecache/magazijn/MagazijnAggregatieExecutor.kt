package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Begrensde, dedicated worker-pool voor de blokkerende magazijn-aggregatie-calls.
 *
 * De gegenereerde [MagazijnClient] is synchroon (`jaxrs-spec`); elke call blokkeert een
 * thread tot het antwoord of de read-timeout. Zou de aggregatie de gedeelde Quarkus
 * default-worker-pool gebruiken, dan kan één trage leverancier die pool laten vollopen en
 * blokkeren ook niet-gerelateerde endpoints (GET/POST /berichten) op een vrije thread. Door
 * de magazijn-calls op een EIGEN begrensde pool te draaien, raakt een trage leverancier
 * alléén deze pool: de default-pool en daarmee de overige endpoints blijven responsief.
 *
 * Het lagere doorvoer-plafond is de bewuste trade-off van deze isolatie; de per-magazijn
 * [MagazijnCircuitBreaker] beperkt de tijd dat een dood magazijn pool-threads bezet houdt
 * (snelle fail zodra het circuit opent).
 *
 * De queue is bewust BEGRENSD ([queueCapaciteit]): een trage-maar-niet-falende leverancier
 * opent het circuit niet, dus zonder grens zou de queue onbeperkt groeien (geheugen-DoS) —
 * precies het overload-voetkanon dat deze feature dicht. Bij een volle queue wordt de taak
 * afgewezen (`RejectedExecutionException` → [MagazijnFault.OVERBELAST]: snelle "tijdelijk niet
 * beschikbaar" i.p.v. bufferen).
 *
 * Bekende beperking (bewuste trade-off van het bounded-pool-alternatief, niet een per-magazijn
 * bulkhead): deze pool wordt door álle magazijnen en ontvangers gedeeld. Tijdens het korte
 * venster waarin een trage leverancier de pool vult vóórdat zijn [MagazijnCircuitBreaker] opent,
 * kunnen ook calls naar gezonde leveranciers tijdelijk worden afgewezen (OVERBELAST). Zodra het
 * circuit van de verantwoordelijke leverancier opent, worden diens calls overgeslagen en herstelt
 * de pool, waarna gezonde leveranciers weer normaal bediend worden. Een harde per-magazijn-garantie
 * zou een per-magazijn concurrency-cap vereisen; dat valt buiten dit alternatief.
 */
@ApplicationScoped
internal class MagazijnAggregatieExecutor(
    @param:ConfigProperty(name = "berichtensessiecache.magazijn-pool.size", defaultValue = "20")
    private val poolSize: Int,
    @param:ConfigProperty(name = "berichtensessiecache.magazijn-pool.queue-capaciteit", defaultValue = "200")
    private val queueCapaciteit: Int,
) {
    private val log = Logger.getLogger(MagazijnAggregatieExecutor::class.java)

    init {
        // Fail-fast bij boot met een sleutel-benoemende melding (ThreadPoolExecutor/
        // LinkedBlockingQueue zouden zelf wel falen, maar generiek). Loopt vóór de
        // executor-constructie hieronder dankzij declaratie-volgorde.
        require(poolSize > 0) { "berichtensessiecache.magazijn-pool.size ($poolSize) moet groter zijn dan 0" }

        require(queueCapaciteit > 0) {
            "berichtensessiecache.magazijn-pool.queue-capaciteit ($queueCapaciteit) moet groter zijn dan 0"
        }
    }

    private val executor: ExecutorService = ThreadPoolExecutor(
        poolSize,
        poolSize,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(queueCapaciteit),
        naamgevendeFactory(),
        // Volle queue → RejectedExecutionException naar de submitter (Mutiny-subscribe), die het
        // als Uni-failure afhandelt. Bewust géén CallerRunsPolicy: dat zou de blocking magazijn-
        // call op de event-loop/aanroep-thread draaien en juist de isolatie ondermijnen.
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun executor(): ExecutorService = executor

    private fun naamgevendeFactory(): ThreadFactory {
        val teller = AtomicInteger(0)

        return ThreadFactory { runnable ->
            Thread(runnable, "fbs-magazijn-aggregatie-${teller.incrementAndGet()}").apply {
                // Daemon zodat een hangende magazijn-call de JVM-shutdown niet blokkeert.
                isDaemon = true
            }
        }
    }

    @PreDestroy
    fun stop() {
        executor.shutdown()

        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                log.warnf("Magazijn-aggregatie-pool niet binnen %ds afgesloten; forceer shutdownNow", SHUTDOWN_GRACE_SECONDS)
                executor.shutdownNow()
            }
        } catch (ex: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val SHUTDOWN_GRACE_SECONDS = 5L
    }
}
