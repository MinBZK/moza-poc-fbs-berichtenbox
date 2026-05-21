package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.scheduler.Scheduled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Borgt de operationeel-cruciale annotatie-eigenschappen van
 * [PublicatieDeliveriesOpschoner.verwijderTerminaleRijen]:
 *  - `every` verwijst naar `magazijn.publicatie.opschonen.interval` zodat ops
 *    de cleanup-cadens config-only kan wijzigen.
 *  - `concurrentExecution = SKIP` voorkomt overlap binnen één pod als een
 *    cleanup langer duurt dan het interval (multi-pod parallelle DELETE is
 *    wasteful maar safe — Postgres rij-locks serialiseren).
 *  - `delay = 5 MINUTES` geeft Flyway/Hibernate-validatie ruimte vóór de
 *    eerste DELETE; voorkomt querying op een nog-niet-bestaande view.
 *
 * Tegenhanger van [PublicatieStreamScheduleAnnotationTest] voor de poller-cadens;
 * een refactor die deze annotaties verwijdert/wijzigt zou anders pas merkbaar
 * worden in productie (outbox-groei of trage opstart-fout).
 */
class PublicatieDeliveriesOpschonerScheduleAnnotationTest {

    private val annotation: Scheduled = PublicatieDeliveriesOpschoner::class.java
        .getDeclaredMethod("verwijderTerminaleRijen")
        .getAnnotation(Scheduled::class.java)

    @Test
    fun `every verwijst naar opschonen-interval property`() {
        assertEquals("{magazijn.publicatie.opschonen.interval}", annotation.every)
    }

    @Test
    fun `concurrentExecution is SKIP`() {
        assertEquals(Scheduled.ConcurrentExecution.SKIP, annotation.concurrentExecution)
    }

    @Test
    fun `startup-delay is 5 minuten`() {
        assertEquals(5L, annotation.delay)
        assertEquals(TimeUnit.MINUTES, annotation.delayUnit)
    }
}
