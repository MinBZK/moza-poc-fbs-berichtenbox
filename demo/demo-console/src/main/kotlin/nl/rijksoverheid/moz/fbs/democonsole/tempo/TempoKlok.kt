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

    override fun start(intervalSeconden: Int, tik: () -> Unit) {
        stop()

        scheduler.newJob(JOB)
            .setInterval("${intervalSeconden}s")
            .setTask { tik() }
            .schedule()
    }

    // Onvoorwaardelijk unschedulen: unscheduleJob op een onbekende identity geeft in deze
    // Quarkus-versie stil null terug (geen exception), dus een eigen "is er iets gepland"-guard
    // voegt niets toe en kan bij een falende schedule() de stop-knop juist permanent blokkeren.
    override fun stop() {
        scheduler.unscheduleJob(JOB)
    }

    private companion object {

        const val JOB = "demo-tempo"
    }
}
