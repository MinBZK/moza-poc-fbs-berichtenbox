package nl.rijksoverheid.moz.fbs.democonsole.bereikbaarheid

import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Optional

class BereikbaarheidServiceTest {

    private fun config(vararg adressen: Pair<String, String?>): BereikbaarheidConfig = mockk {
        every { bereikbaarheid() } returns adressen.associate { (naam, url) ->
            naam to mockk<BereikbaarheidConfig.Component> { every { url() } returns Optional.ofNullable(url) }
        }
    }

    @Test
    fun `een leeg of ontbrekend adres zet de controle van dat component uit`() {
        // Dezelfde afspraak als bij de Toxiproxy-adressen: een omgeving laat een component weg door
        // de env-var leeg te laten, zonder eigen profiel of codepad.
        val config = config(
            "magazijn-a" to "http://magazijn-a",
            "magazijn-b" to "",
            "uitvraag" to "  ",
            "profiel" to null,
        )

        assertEquals(mapOf("magazijn-a" to "http://magazijn-a"), teControleren(config, simulator = true))
    }

    @Test
    fun `de service wordt bij het opstarten gebouwd, zodat een onbruikbaar adres de start laat falen`() {
        // Zonder `@Startup` bouwt CDI de bean pas bij de eerste uitlezing; dan blijft geen enkele
        // test rood, maar faalt een typefout in de configuratie pas midden in een demonstratie.
        assertTrue(BereikbaarheidService::class.java.isAnnotationPresent(Startup::class.java))
    }

    @Test
    fun `de controle draait ook zonder open paneel, zonder dat rondes op elkaar stapelen`() {
        // Zonder deze ronde staat een uitval die vanzelf herstelt alleen in het log als er toevallig
        // iemand naar het paneel keek.
        val gepland = BereikbaarheidService::class.java.getMethod("controleerOpDeAchtergrond")
            .getAnnotation(Scheduled::class.java)

        assertNotNull(gepland, "de achtergrondcontrole is niet gepland")
        assertEquals("{bereikbaarheid.controle-interval}", gepland.every)
        assertEquals(Scheduled.ConcurrentExecution.SKIP, gepland.concurrentExecution)
    }

    @Test
    fun `zonder ingerichte adressen valt er niets te controleren`() {
        assertEquals(emptyMap<String, String>(), teControleren(config(), simulator = true))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `de simulator telt alleen mee als de omgeving hem kent`(simulator: Boolean) {
        // Het adres heeft een default en is dus altijd gezet; zonder deze toets staat een omgeving
        // zonder simulator de hele demo met een rode chip.
        val config = config("simulator" to "http://simulator", "uitvraag" to "http://uitvraag")

        val verwacht = if (simulator) setOf("simulator", "uitvraag") else setOf("uitvraag")

        assertEquals(verwacht, teControleren(config, simulator).keys)
    }
}
