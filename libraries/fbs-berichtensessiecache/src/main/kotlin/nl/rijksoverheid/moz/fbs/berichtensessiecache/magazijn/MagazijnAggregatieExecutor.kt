package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
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
 */
@ApplicationScoped
internal class MagazijnAggregatieExecutor(
    @param:ConfigProperty(name = "berichtensessiecache.magazijn-pool.size", defaultValue = "20")
    private val poolSize: Int,
) {
    private val log = Logger.getLogger(MagazijnAggregatieExecutor::class.java)

    private val executor: ExecutorService = Executors.newFixedThreadPool(poolSize, naamgevendeFactory())

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
