package nl.rijksoverheid.moz.fbs.democonsole

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.Properties

/**
 * Pint eigenschappen van `application.properties` die alleen buiten een testomgeving stuk kunnen
 * gaan.
 *
 * De eerste: de scheduler start geforceerd. `SchedulerTempoKlok` plant zijn tik-taak uitsluitend
 * programmatisch (`Scheduler.newJob(...)`), en zonder een gestarte scheduler gooit `newJob()` een
 * `UnsupportedOperationException` zodra de tempo-knoppen worden gebruikt — de berichtenstroom start
 * dan nergens, zonder dat een test tegen de scheduler-test-dubbel dat opmerkt. `ProxyBootstrap`
 * draagt weliswaar een `@Scheduled`-methode, waarmee de scheduler ook in de default 'normal'-modus
 * zou starten, maar de tempo-knoppen mogen niet afhangen van het voortbestaan van een methode in een
 * andere klasse.
 *
 * Bewust géén `@QuarkusTest`: dit leest het bestand rechtstreeks van disk, zodat de test ook zonder
 * Docker draait.
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

    @Test
    fun `de default rest-client-exception-mapper staat uit`() {
        // Staat hij aan, dan mapt de client élke 4xx en 5xx naar een WebApplicationException, ook op
        // methodes die een Response teruggeven. De statuscontroles in deze module — de aanlevering,
        // de storingsknoppen, de foutieve aanlevering — worden dan onbereikbaar, en een 503 van een
        // magazijn komt als exception binnen in plaats van als antwoord.
        assertEquals("true", properties.getProperty("microprofile.rest.client.disable.default.mapper"))
    }

    @Test
    fun `de reconcile-interval staat buiten de demo-prefix`() {
        // `demo.*` is geclaimd door @ConfigMapping(prefix="demo"): elke property daaronder moet op
        // een mapping-member vallen, anders faalt het booten met SRCFG00050. Deze waarde hoort bij
        // geen enkele mapping, dus hij staat er bewust naast.
        assertEquals("\${TOXIPROXY_RECONCILE_INTERVAL:30s}", properties.getProperty("toxiproxy.reconcile-interval"))
    }

    /**
     * De bereikbaarheid controleert standaard hetzelfde adres dat de console al voor dat component
     * gebruikt. Afgeleid en niet overgeschreven: een omgeving die alleen `MAGAZIJN_A_URL` verzet,
     * hoort niet een ánder magazijn gezond te zien dan het magazijn waar ze aan levert.
     */
    @ParameterizedTest
    @MethodSource("bereikbaarheidsbronnen")
    fun `de bereikbaarheid valt terug op het adres dat de console al gebruikt`(
        component: String,
        variabele: String,
        bron: String,
    ) {
        val adres = properties.getProperty(bron)

        assertNotNull(adres, "bron $bron ontbreekt in application.properties")
        assertEquals("\${$variabele:$adres}", properties.getProperty("demo.bereikbaarheid.\"$component\".url"))
    }

    @Test
    fun `de personadienst wordt standaard op zijn eigen poort gecontroleerd`() {
        // Uit de module zelf gelezen: verzet iemand die poort, dan staat de chip anders lokaal
        // blijvend op onbereikbaar terwijl de dienst gewoon draait.
        // In microprofile-config.properties: demo-personas is ook een jar die deze console inleest, en
        // een application.properties in die jar zou de configuratie van de console overschaduwen.
        val personas = Properties().apply {
            File("../demo-personas/src/main/resources/META-INF/microprofile-config.properties").inputStream()
                .use { load(it) }
        }
        val poort = personas.getProperty("quarkus.http.port")

        assertNotNull(poort, "demo-personas noemt geen quarkus.http.port")
        assertEquals(
            "\${BEREIKBAARHEID_PERSONADIENST_URL:http://localhost:$poort}",
            properties.getProperty("demo.bereikbaarheid.\"personadienst\".url"),
        )
    }

    @Test
    fun `de achtergrondcontrole staat buiten de demo-prefix en onder test uit`() {
        // Buiten `demo.*` om de reden die bij de reconcile-interval staat. Onder test draait geen
        // component, dus elke ronde zou alleen waarschuwingen in de testuitvoer zetten.
        assertEquals("\${BEREIKBAARHEID_INTERVAL:30s}", properties.getProperty("bereikbaarheid.controle-interval"))
        assertEquals("off", properties.getProperty("%test.bereikbaarheid.controle-interval"))
    }

    private companion object {

        @JvmStatic
        fun bereikbaarheidsbronnen() = listOf(
            Arguments.of("magazijn-a", "BEREIKBAARHEID_MAGAZIJN_A_URL", "demo.magazijnen.\"00000000000000100000\".url"),
            Arguments.of("magazijn-b", "BEREIKBAARHEID_MAGAZIJN_B_URL", "demo.magazijnen.\"00000001823288444000\".url"),
            Arguments.of("uitvraag", "BEREIKBAARHEID_UITVRAAG_URL", "quarkus.rest-client.uitvraag.url"),
            Arguments.of("simulator", "BEREIKBAARHEID_SIMULATOR_URL", "quarkus.rest-client.magazijnsimulator.url"),
        )
    }
}
