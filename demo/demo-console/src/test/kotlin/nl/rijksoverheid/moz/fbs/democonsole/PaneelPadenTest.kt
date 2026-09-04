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

    private val script: String = File(SCRIPT).readText()

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

    /**
     * Elk `{veld}` in een knop-adres moet een element in dezelfde pagina aanwijzen, én in `VELDEN`
     * staan.
     *
     * Beide falen stil. Wijst het niet naar een element, dan geeft `vulPadIn` null terug en keert
     * `voerUit` terug zonder melding of merkteken: de knop doet niets en niets zegt waarom. Staat
     * het veld niet in `VELDEN`, dan bewaart het paneel de invoer niet en herstelt hij hem ook niet
     * — een keuzelijst valt na een refresh stil terug op zijn eerste optie.
     */
    @Test
    fun `elk veld in een knop-adres bestaat en wordt bewaard`() {
        val velden = uitPaneel("""\{(\w+)}""").toSet()
        val elementen = uitPaneel("""id="([^"]+)"""").toSet()
        val bewaard = Regex("""const VELDEN = \[([^\]]+)]""")
            .find(script)
            ?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().trim('\'') }
            ?.toSet()
            .orEmpty()

        assertTrue(velden.isNotEmpty(), "geen enkel {veld} gevonden in $PANEEL")
        assertTrue(bewaard.isNotEmpty(), "de VELDEN-lijst niet gevonden in $SCRIPT")
        assertEquals(emptySet<String>(), velden - elementen, "knop-adres verwijst naar een onbekend veld")
        assertEquals(emptySet<String>(), velden - bewaard, "veld uit een knop-adres ontbreekt in VELDEN")
    }

    /**
     * Een `data-samenvatting` die `bediening.js` niet kent, valt terug op een kaal groen "Gelukt":
     * de knop meldt succes zonder de samenvatting die zegt wát er gebeurde.
     *
     * Eerst het blok afbakenen en dan pas de sleutels lezen: zoeken over het hele script accepteert
     * ook de sleutels van de buur-objecten, en juist daar komt de verwisseling vandaan — een
     * `data-actie`-naam als `ververs-box` of een proxy-naam als `magazijn-a` in dit attribuut.
     */
    @Test
    fun `elke knop noemt een samenvatting die het script kent`() {
        val sleutels = sleutelsVan("SAMENVATTINGEN")
        val uitPaneel = uitPaneel("""data-samenvatting="([^"]+)"""").toSet()

        assertTrue(uitPaneel.isNotEmpty(), "geen enkele data-samenvatting gevonden in $PANEEL")
        assertTrue(sleutels.isNotEmpty(), "het SAMENVATTINGEN-blok niet gevonden in $SCRIPT")

        // Eerst bewijzen dat de meting discrimineert: deze naam staat wél in het script, in een
        // ander object. Accepteert de test hem, dan bewaakt hij niets.
        assertTrue("ververs-box" !in sleutels, "de sleutels komen niet uit SAMENVATTINGEN alleen")

        assertEquals(
            emptySet<String>(),
            uitPaneel - sleutels,
            "knoppen met een samenvatting die niet in SAMENVATTINGEN staat",
        )
    }

    /** De sleutels van één object-literal op het hoogste niveau van `bediening.js`. */
    private fun sleutelsVan(objectnaam: String): Set<String> {
        val blok = Regex("""^const $objectnaam = \{$(.*?)^};$""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .find(script)
            ?.groupValues?.get(1)
            .orEmpty()

        return Regex("""^ {4}'?([\w-]+)'?:""", RegexOption.MULTILINE).findAll(blok).map { it.groupValues[1] }.toSet()
    }

    /**
     * De bovengrens staat zowel in het invoerveld als in de resource. Lopen ze uiteen, dan weigert
     * de server een waarde die de browser aanbiedt — of andersom, en dan is de grens er niet.
     */
    @Test
    fun `het aantal-veld voor een gericht bericht deelt zijn grenzen met de resource`() {
        val veld = Regex("""<input id="berichtAantal"[^>]*>""").find(paneel)?.value

        assertTrue(veld != null, "het veld berichtAantal niet gevonden in $PANEEL")
        assertEquals("""min="1"""", Regex("""min="\d+"""").find(veld!!)?.value)
        assertEquals("""max="${DemoResource.MAX_BERICHTEN}"""", Regex("""max="\d+"""").find(veld)?.value)
    }

    private fun uitPaneel(patroon: String): List<String> =
        Regex(patroon).findAll(paneel).map { it.groupValues[1] }.toList()

    private companion object {

        const val PANEEL = "src/main/resources/META-INF/resources/index.html"

        const val SCRIPT = "src/main/resources/META-INF/resources/bediening.js"

        const val PROPERTIES = "src/main/resources/application.properties"
    }
}
