package nl.rijksoverheid.moz.fbs.magazijnsimulator.pool

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tegen de echte pool en niet tegen een dubbel: wat hier fout kan gaan is juist of de tellers uit
 * Agroal komen. Staat `quarkus.datasource.jdbc.enable-metrics` uit, dan is alles nul en meldt de
 * regel een pool die nooit iets doet — een dubbel zou dat niet merken.
 */
@QuarkusTest
class PoolmonitorTest {

    @Inject
    lateinit var monitor: Poolmonitor

    @Test
    fun `de monitor leest de tellers van de draaiende pool`() {
        // De testconfiguratie zet de pool op twintig; de meting hoort dat te melden en niet de 120
        // van de demo.
        val moment = monitor.meet()

        assertEquals(20, moment.max, "de gemeten maximumgrootte hoort uit de datasource te komen")
        assertTrue(moment.opgezet > 0, "na Flyway en de tests horen er connections opgezet te zijn")
        assertTrue(moment.piek > 0, "de pool is tijdens de tests gebruikt, dus de piek hoort boven nul te liggen")
        assertTrue(moment.inGebruik + moment.vrij <= moment.max, "bezetting kan de pool niet overschrijden")
    }

    @Test
    fun `loggen laat de monitor niet struikelen en onthoudt de meting`() {
        monitor.log()
        monitor.log()

        assertTrue(monitor.meet().regel().startsWith("pool: "), "de regel hoort leesbaar te blijven")
    }
}
