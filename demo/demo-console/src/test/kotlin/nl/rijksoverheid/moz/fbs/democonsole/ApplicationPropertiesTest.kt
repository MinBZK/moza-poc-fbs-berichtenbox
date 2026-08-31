package nl.rijksoverheid.moz.fbs.democonsole

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * Pint twee eigenschappen van `application.properties` die alleen buiten een testomgeving stuk
 * kunnen gaan.
 *
 * De eerste: de scheduler start geforceerd. `SchedulerTempoKlok` plant
 * zijn tik-taak uitsluitend programmatisch (`Scheduler.newJob(...)`); er is geen
 * `@Scheduled`-business-methode die de scheduler in de default 'normal'-modus zou starten.
 * Zonder `start-mode=forced` blijft de scheduler uit en gooit `newJob()` een
 * `UnsupportedOperationException` zodra de tempo-knoppen worden gebruikt — de berichtenstroom
 * start dan nergens, zonder dat een test tegen de scheduler-test-dubbel dat opmerkt. Bewust géén
 * `@QuarkusTest`: dit leest het bestand rechtstreeks van disk, zodat de test ook zonder Docker
 * draait.
 */
class ApplicationPropertiesTest {

    private val properties = Properties().apply {
        File("src/main/resources/application.properties").inputStream().use { load(it) }
    }

    @Test
    fun `scheduler start geforceerd, ondanks het ontbreken van een Scheduled-methode`() {
        assertEquals("forced", properties.getProperty("quarkus.scheduler.start-mode"))
    }

    @Test
    fun `de Redis-verbinding draagt een wachtwoord uit de omgeving, met een lege default`() {
        // Lokaal draait Redis zonder wachtwoord en op een gedeelde omgeving mét; een client die de
        // property niet kent krijgt daar 'NOAUTH Authentication required' op de eerste opdracht —
        // en dat is een fout die pas bij een druk op de knop verschijnt, niet bij het starten.
        // De lege default houdt het lokaal werkend: SmallRye leest een lege waarde als afwezig.
        assertEquals("\${REDIS_PASSWORD:}", properties.getProperty("quarkus.redis.password"))
    }
}
