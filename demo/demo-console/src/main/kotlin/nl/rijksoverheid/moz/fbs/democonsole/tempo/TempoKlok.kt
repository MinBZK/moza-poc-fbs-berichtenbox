package nl.rijksoverheid.moz.fbs.democonsole.tempo

import io.quarkus.scheduler.Scheduler
import jakarta.enterprise.context.ApplicationScoped

/**
 * Plant en annuleert de tik-taak. Aparte laag zodat [TempoService] zonder draaiende Quarkus te
 * toetsen is: de scheduler-API is een keten van fluent-aanroepen die zich slecht laat mocken, en
 * deze module houdt zijn tests bewust pure JVM.
 */
interface TempoKlok {

    fun start(intervalSeconden: Int, tik: () -> Unit)

    fun stop()
}

@ApplicationScoped
class SchedulerTempoKlok(private val scheduler: Scheduler) : TempoKlok {

    // Eigen administratie i.p.v. de scheduler bevragen: unscheduleJob op een niet-geplande taak
    // is per versie verschillend (stil of een fout), en de stop-knop mag nooit zelf omvallen.
    private var gepland = false

    override fun start(intervalSeconden: Int, tik: () -> Unit) {
        stop()

        scheduler.newJob(JOB)
            .setInterval("${intervalSeconden}s")
            .setTask { tik() }
            .schedule()

        gepland = true
    }

    override fun stop() {
        if (gepland) {
            scheduler.unscheduleJob(JOB)

            gepland = false
        }
    }

    private companion object {

        const val JOB = "demo-tempo"
    }
}
