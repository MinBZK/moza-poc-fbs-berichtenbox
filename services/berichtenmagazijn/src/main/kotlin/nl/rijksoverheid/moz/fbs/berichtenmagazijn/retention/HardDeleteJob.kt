package nl.rijksoverheid.moz.fbs.berichtenmagazijn.retention

import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution
import jakarta.enterprise.context.ApplicationScoped

/**
 * Cron-entrypoint voor de hard-delete-job. Quarkus Scheduler leest de
 * cron-expressie uit `retentie.hard-delete.cron` (default dagelijks 03:00).
 *
 * `concurrentExecution = SKIP` zorgt dat een tweede tick binnen dezelfde JVM
 * niet start voordat de vorige klaar is. Cross-pod-veiligheid komt uit
 * `FOR UPDATE SKIP LOCKED` in [HardDeleteService].
 */
@ApplicationScoped
class HardDeleteJob(
    private val service: HardDeleteService,
    private val meting: HardDeleteMeting,
) {

    @Scheduled(
        cron = "{retentie.hard-delete.cron}",
        concurrentExecution = ConcurrentExecution.SKIP,
        identity = "hard-delete-soft-deleted-berichten",
    )
    fun fire() {
        try {
            service.run()
        } catch (ex: RuntimeException) {
            // Een ronde die als geheel afbreekt, hoort in de meting; de scheduler logt de exceptie zelf.
            meting.rondeAfgebroken()
            throw ex
        }
    }
}
