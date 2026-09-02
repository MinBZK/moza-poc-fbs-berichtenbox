package nl.rijksoverheid.moz.fbs.democonsole

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import nl.rijksoverheid.moz.fbs.democonsole.generator.GeneratorProducer
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorService
import nl.rijksoverheid.moz.fbs.demopersonas.Identificatiecheck
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaBron
import nl.rijksoverheid.moz.fbs.demopersonas.TestPersonas
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.random.Random
import kotlin.streams.asSequence

/**
 * Bewaakt dat de bronnen die samen de demo-vulling bepalen niet uiteenlopen: de persona-lijst van de
 * personadienst (`META-INF/microprofile-config.properties`), de curated `dataset/basis.json`, de
 * ondernemerslijst van de simulator, de magazijn-URL's in `application.properties` en de
 * profielservice-stubs onder `wiremock/demo-profiel/`.
 *
 * Divergeert er één, dan weigert het magazijn de aanlevering met een 403 en meldt de console dat er
 * nul berichten zijn aangeleverd — midden in een demo, zonder aanwijzing waar het misging.
 */
class DemoDatasetConsistentieTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    /** De echte generator: organisaties uit [GeneratorProducer], persona's uit de personadienst. */
    private fun generator() = GeneratorProducer().generator(TestPersonas.uitConfiguratie())

    /** De ingerichte persona's die hun berichten uit de keten halen; zie [PersonaBron]. */
    private fun ketenPersonas() = TestPersonas.uitConfiguratie().alle().filter { it.bron == PersonaBron.KETEN }

    @Test
    fun `de echte generator-configuratie voldoet aan haar eigen invarianten`() {
        // GeneratorProducer is lazy: zonder deze aanroep raakt CI de echte persona- en
        // organisatielijst nooit aan en blijkt een typfout daarin pas bij de eerste klik. De
        // persona's komen uit application.properties, dezelfde bron als bij het starten.
        val generator = generator()

        assertTrue(generator.genereer(aantal = 5, random = Random(1)).isNotEmpty())
    }

    @Test
    fun `elke gegenereerde opdracht is door de profiel-stubs toegestaan`() {
        val opdrachten = generator().genereer(aantal = 200, random = Random(2))

        assertTrue(opdrachten.isNotEmpty(), "zonder opdrachten toetst deze test niets")
        controleerTegenProfielStubs(opdrachten)
    }

    @Test
    fun `de basisdataset is door de profiel-stubs toegestaan`() {
        val opdrachten = Basisdataset(mapper).laad()

        assertTrue(opdrachten.isNotEmpty(), "basis.json mag niet leeg zijn")
        controleerTegenProfielStubs(opdrachten)
    }

    @Test
    fun `in de basisdataset is de afzender altijd het magazijn zelf`() {
        // Eén magazijn = één organisatie: een afwijkende afzender levert een 403 op.
        Basisdataset(mapper).laad().forEach {
            assertEquals(it.magazijnOin, it.verzoek.afzender, "afzender moet de OIN van het doelmagazijn zijn")
        }
    }

    /**
     * Een persona die zelf magazijnen noemt, krijgt zijn berichten van dit paneel aangeleverd — en
     * dan hoort er bij élk van die magazijnen iets te staan. Op magazijn én ontvanger dus, niet op
     * ontvanger alleen: een persona die bij één van zijn organisaties leeg blijft, ziet daar een
     * lege map terwijl dat magazijn keurig met nul berichten antwoordt en de ophaalronde slaagt.
     * Tijdens een demo is dat niet te onderscheiden van een keten die stukstaat.
     *
     * De basisvulling en niet de generator: de knop die willekeurige berichten opvoert bedient elke
     * persona met magazijnen al, en dekt dit gat dus niet af — de beginsituatie van een demo komt
     * uit `basis.json`.
     */
    @Test
    fun `elke persona met eigen magazijnen heeft daar berichten in de basisdataset`() {
        val bakken = Basisdataset(mapper).laad()
            .map { it.magazijnOin to "${it.verzoek.ontvanger.type}:${it.verzoek.ontvanger.waarde}" }
            .toSet()

        val verwacht = ketenPersonas()
            .filter { it.magazijnen.isNotEmpty() }
            .flatMap { persona -> persona.magazijnen.map { persona to it } }

        assertTrue(verwacht.isNotEmpty(), "zonder persona's met eigen magazijnen toetst deze test niets")

        // De persona-id en niet zijn identificatienummer: de id wijst de bediener rechtstreeks naar
        // de regel in de personadienst die hij naast basis.json moet leggen.
        verwacht.forEach { (persona, magazijn) ->
            assertTrue(
                (magazijn to persona.ontvanger) in bakken,
                "persona '${persona.id}' noemt magazijn $magazijn maar heeft daar geen bericht in basis.json",
            )
        }
    }

    /**
     * De tegenhanger: wie geen eigen magazijnen noemt, haalt zijn berichten uit de gesimuleerde
     * magazijnen en moet dus bij de ondernemers van de simulator staan. Zonder deze eis kan een
     * persona in beide lijsten ontbreken — precies wat de drie proeftuin-persona's overkwam.
     *
     * Let op de reikwijdte: dit toetst de inrichting, niet een omgeving. Staat de simulator er niet,
     * dan meldt `HerstelService` dat als overgeslagen en blijven deze persona's alsnog leeg.
     */
    @Test
    fun `elke keten-persona zonder eigen magazijnen staat bij de ondernemers van de simulator`() {
        val zonderEigenMagazijnen = ketenPersonas().filter { it.magazijnen.isEmpty() }

        assertTrue(zonderEigenMagazijnen.isNotEmpty(), "zonder zulke persona's toetst deze test niets")

        zonderEigenMagazijnen.forEach {
            assertTrue(
                it.ontvanger in SimulatorService.ONDERNEMERS,
                "persona '${it.id}' krijgt van geen enkele vulling berichten: geen eigen magazijnen " +
                    "en niet bij de ondernemers van de simulator",
            )
        }
    }

    @Test
    fun `elk magazijn uit de datasets heeft een URL in de configuratie`() {
        val geconfigureerd = magazijnenUitConfig()
        val gebruikt = (Basisdataset(mapper).laad() + generator().genereer(200, Random(3)))
            .map { it.magazijnOin }
            .toSet()

        assertTrue(gebruikt.isNotEmpty())
        assertTrue(
            geconfigureerd.containsAll(gebruikt),
            "geen demo.magazijnen-URL voor: ${gebruikt - geconfigureerd} (geconfigureerd: $geconfigureerd)",
        )
    }

    @Test
    fun `elke persona in de profiel-stubs heeft een geldig identificatienummer`() {
        val stubs = profielStubs()

        assertTrue(stubs.isNotEmpty(), "geen profiel-stubs gevonden onder $STUB_MAP")

        stubs.keys.forEach { (type, waarde) -> Identificatiecheck.valideer(type, waarde) }
    }

    @Test
    fun `de BSN-personas komen uit de 999-testreeks`() {
        // Een elfproef-geldig nummer buiten die reeks kan van een bestaand persoon zijn; deze repo
        // is publiek, dus dat verschil is het hele punt.
        profielStubs().keys
            .filter { (type, _) -> type == "BSN" }
            .forEach { (_, waarde) ->
                assertTrue(waarde.startsWith("999"), "demo-BSN '$waarde' hoort in de 999-testreeks")
            }
    }

    private fun controleerTegenProfielStubs(opdrachten: List<AanleverOpdracht>) {
        val stubs = profielStubs()

        opdrachten.forEach { opdracht ->
            val sleutel = opdracht.verzoek.ontvanger.type to opdracht.verzoek.ontvanger.waarde
            val toegestaan = stubs[sleutel]

            assertTrue(toegestaan != null, "geen profiel-stub voor ontvanger-type ${sleutel.first} in $STUB_MAP")
            assertTrue(
                opdracht.magazijnOin in toegestaan!!,
                "ontvanger van type ${sleutel.first} heeft geen opt-in voor magazijn ${opdracht.magazijnOin}",
            )
        }
    }

    /** (type, waarde) → de organisatie-OIN's waarvoor die persona een actieve voorkeur heeft. */
    private fun profielStubs(): Map<Pair<String, String>, Set<String>> {
        val map = Path.of(STUB_MAP)

        assertTrue(Files.isDirectory(map), "profiel-stubs niet gevonden op $STUB_MAP (draait de test vanaf de module-root?)")

        return Files.list(map).use { paden ->
            paden.asSequence()
                .filter { it.toString().endsWith(".json") }
                .map { mapper.readTree(it.toFile()) }
                .mapNotNull { stub -> sleutelVan(stub)?.let { it to oinsVan(stub) } }
                .toMap()
        }
    }

    /** Uit `urlPathPattern` (…/v1/BSN/999993653) het paar (type, waarde) halen. */
    private fun sleutelVan(stub: JsonNode): Pair<String, String>? {
        val pad = stub.path("request").path("urlPathPattern").asText("").split("/")

        return if (pad.size < 2) null else (pad[pad.size - 2] to pad.last()).takeIf { it.first in TYPEN }
    }

    private fun oinsVan(stub: JsonNode): Set<String> =
        stub.path("response").path("jsonBody").path("voorkeuren")
            .filter { it.path("voorkeurType").asText() == "OntvangViaBerichtenbox" && it.path("waarde").asText() == "true" }
            .flatMap { it.path("scopes") }
            .map { it.path("partij").path("identificatieNummer").asText() }
            .filter { it.isNotBlank() }
            .toSet()

    /** De OIN's waarvoor `application.properties` een aanlever-URL kent (`demo.magazijnen."<OIN>".url`). */
    private fun magazijnenUitConfig(): Set<String> {
        val eigenschappen = Properties()

        javaClass.classLoader.getResourceAsStream("application.properties").use { eigenschappen.load(it) }

        return eigenschappen.stringPropertyNames()
            .mapNotNull { SLEUTEL.matchEntire(it)?.groupValues?.get(1) }
            .toSet()
    }

    private companion object {

        // Relatief aan de module-root (Surefire's werkdirectory), niet aan het classpath: deze
        // stubs zijn geen resource van de module maar een compose-mount uit de repo-root.
        const val STUB_MAP = "../../wiremock/demo-profiel/mappings"

        val TYPEN = setOf("BSN", "KVK", "RSIN")

        val SLEUTEL = Regex("""demo\.magazijnen\."(\d+)"\.url""")
    }
}
