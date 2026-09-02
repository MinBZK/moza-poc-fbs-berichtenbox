package nl.rijksoverheid.moz.fbs.democonsole

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * Bewaakt welke adressen de knoppen van het paneel aanroepen.
 *
 * Een knop die naar een adres wijst dat deze module niet beantwoordt, faalt met een 404 die eruit
 * ziet als een kapotte keten — en dat merk je pas tijdens een demo, want lokaal zet de demo-proxy
 * hetzelfde pad wél door naar een andere dienst. Rechtstreeks op poort 8095 en op een gedeelde
 * omgeving is er geen proxy.
 *
 * Bewust géén `@QuarkusTest`: dit leest het bestand rechtstreeks van schijf en draait dus zonder
 * Docker.
 */
class PaneelPadenTest {

    private val paneel: String = File(PANEEL).readText()

    private val paden: List<String> = uitPaneel("""data-pad="([^"]+)"""")

    @Test
    fun `het paneel draagt knoppen met een adres`() {
        // Zonder deze assertie zouden de tests hieronder over een lege lijst lopen en altijd slagen,
        // ook als de regex nooit meer iets vindt.
        assertTrue(paden.isNotEmpty(), "geen enkele data-pad gevonden in index.html")
    }

    @Test
    fun `elke knop wijst naar de eigen demo-API`() {
        // Het paneel praat uitsluitend met de console zelf; alles daarbuiten (de uitvraag, de
        // berichtenbox) loopt via de pagina's, niet via deze knoppen.
        val vreemd = paden.filterNot { it.startsWith("/api/demo/") }

        assertEquals(emptyList<String>(), vreemd, "knoppen horen de eigen /api/demo-API aan te roepen")
    }

    @Test
    fun `geen knop roept het adres van de personadienst aan`() {
        // `personadienst.endpoint=false` schakelt dat adres hier juist uit — PaneelContractTest pint
        // vast dat het 404 hoort te geven. De lijst komt uit /api/demo/omgeving, uit dezelfde bron.
        assertTrue(
            "/api/demo/personas" !in paden,
            "het paneel hoort de personalijst uit /api/demo/omgeving te lezen",
        )
    }

    /**
     * De storingsknoppen hangen aan `data-proxy`. Een naam die niet in de configuratie staat, faalt
     * niet zichtbaar maar *stil*: `pasOmgevingToe` zet `knop.hidden` op alles wat niet in
     * `/api/demo/omgeving` voorkomt, dus de knop verdwijnt gewoon — geen 400, geen log, geen rode
     * chip. Wie het runbook volgt concludeert dan dat deze omgeving dat scenario niet ondersteunt.
     */
    @Test
    fun `elke storingsknop noemt een geconfigureerde proxy`() {
        val uitPaneel = uitPaneel("""data-proxy="([^"]+)"""").toSet()
        val uitConfiguratie = Properties()
            .apply { File(PROPERTIES).inputStream().use { load(it) } }
            .stringPropertyNames()
            .mapNotNull { Regex("""demo\.toxiproxy\."([^"]+)"\.url""").matchEntire(it)?.groupValues?.get(1) }
            .toSet()

        assertTrue(uitPaneel.isNotEmpty(), "geen enkele data-proxy gevonden in $PANEEL")
        assertTrue(uitConfiguratie.isNotEmpty(), "geen enkele demo.toxiproxy-url gevonden in $PROPERTIES")
        assertEquals(emptySet<String>(), uitPaneel - uitConfiguratie, "knoppen voor een onbekende proxy")
    }

    private fun uitPaneel(patroon: String): List<String> =
        Regex(patroon).findAll(paneel).map { it.groupValues[1] }.toList()

    private companion object {

        const val PANEEL = "src/main/resources/META-INF/resources/index.html"

        const val PROPERTIES = "src/main/resources/application.properties"
    }
}
