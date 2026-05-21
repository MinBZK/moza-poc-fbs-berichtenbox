package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.scheduler.Scheduled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Borgt de operationeel-cruciale annotatie-eigenschappen van
 * [PublicatieStream.pollronde]:
 *  - `concurrentExecution = SKIP` voorkomt dat een trage ronde overlapped wordt
 *    door een nieuwe ronde op dezelfde instance (de DB-side `SKIP LOCKED`
 *    regelt parallelisme tussen instanties, niet binnen één instance).
 *  - `every` verwijst naar de configureerbare property zodat ops niet hoeven
 *    deployen om de poll-cadens te wijzigen.
 *
 * Een refactor die deze annotaties verwijdert/wijzigt zou anders pas in
 * productie merkbaar zijn (CPU-spike bij overlap, of starvation bij verkeerd
 * geconfigureerde interval).
 */
class PublicatieStreamScheduleAnnotationTest {

    @Test
    fun `pollronde heeft concurrentExecution SKIP`() {
        val annotation = PublicatieStream::class.java
            .getDeclaredMethod("pollronde")
            .getAnnotation(Scheduled::class.java)
        assertEquals(Scheduled.ConcurrentExecution.SKIP, annotation.concurrentExecution)
    }

    @Test
    fun `pollronde every verwijst naar polling-interval property`() {
        val annotation = PublicatieStream::class.java
            .getDeclaredMethod("pollronde")
            .getAnnotation(Scheduled::class.java)
        assertEquals("{magazijn.publicatie.polling.interval}", annotation.every)
    }
}
