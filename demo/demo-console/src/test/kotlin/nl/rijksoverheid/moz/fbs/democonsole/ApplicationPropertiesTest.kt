package nl.rijksoverheid.moz.fbs.democonsole

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * Pint dat `application.properties` de scheduler geforceerd start. `SchedulerTempoKlok` plant
 * zijn tik-taak uitsluitend programmatisch (`Scheduler.newJob(...)`); er is geen
 * `@Scheduled`-business-methode die de scheduler in de default 'normal'-modus zou starten.
 * Zonder `start-mode=forced` blijft de scheduler uit en gooit `newJob()` een
 * `UnsupportedOperationException` zodra de tempo-knoppen worden gebruikt — de berichtenstroom
 * start dan nergens, zonder dat een test tegen de scheduler-test-dubbel dat opmerkt. Bewust géén
 * `@QuarkusTest`: dit leest het bestand rechtstreeks van disk, zodat de test ook zonder Docker
 * draait.
 */
class ApplicationPropertiesTest {

    @Test
    fun `scheduler start geforceerd, ondanks het ontbreken van een Scheduled-methode`() {
        val properties = Properties().apply {
            File("src/main/resources/application.properties").inputStream().use { load(it) }
        }

        assertEquals("forced", properties.getProperty("quarkus.scheduler.start-mode"))
    }
}
