package nl.rijksoverheid.moz.fbs.democonsole.simulator

import nl.rijksoverheid.moz.fbs.demopersonas.TestPersonas
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * De vier ondernemers staan op meerdere plekken, en niets dwingt ze bij elkaar te houden.
 *
 * Faalscenario zonder deze test: iemand wijzigt een identificatienummer in het generatiescript — dat
 * script zegt zelf dat alleen de gróóttes tellen en niet de nummers, dus dat is een uitnodiging. De
 * vul-knop zet dan berichten klaar voor een ontvanger die geen persona meer is, de demo toont lege
 * magazijnen, en geen enkele test wordt rood.
 *
 * Het script wordt met een reguliere expressie gelezen. Dat is bewust minimaal: de bedoeling is de
 * lijst te vergelijken, niet Python na te bouwen. Vindt de expressie niets, dan faalt de test —
 * "nul verschillen" is anders niet te onderscheiden van "niets gemeten".
 */
class OndernemersConsistentieTest {

    private val uitScript: List<Pair<String, Int>> = lees()

    private val ondernemersUitScript: List<String> = uitScript.map { it.first }

    @Test
    fun `de console kent dezelfde ondernemers als het generatiescript`() {
        assertEquals(ondernemersUitScript, SimulatorService.ONDERNEMERS)
    }

    /**
     * Elke ondernemer die berichten krijgt, moet ook in de keuzelijst van de Berichtenbox staan.
     * Anders kan niemand hem tijdens de demo aanklikken en is de vulling onzichtbaar.
     */
    @Test
    fun `elke ondernemer bestaat als persona in de console`() {
        val personas = TestPersonas.uitConfiguratie().alle().map { it.ontvanger }.toSet()

        SimulatorService.ONDERNEMERS.forEach { ondernemer ->
            assertTrue(ondernemer in personas, "$ondernemer staat niet in demo.personas.*")
        }
    }

    /**
     * De rookproef bevraagt dezelfde vier ondernemers en controleert bij hoeveel organisaties ze
     * uitkomen. Hij staat buiten de reactor, dus niets houdt hem bij het generatiescript.
     *
     * Faalscenario zonder deze test: een identificatienummer wijzigt wél in `basis.json`, de console
     * en het script, maar niet in `smoke.sh`. De rookproef faalt dan met "klein bedrijf bevroeg 0
     * organisaties, verwacht 15" en wijst in zijn eigen foutregel naar een oorzaak die er niet is —
     * of het generatiescript wel gedraaid heeft.
     */
    @Test
    fun `de rookproef bevraagt dezelfde ondernemers, met dezelfde aantallen`() {
        val script = File(ROOT, "demo/smoke.sh")

        assertTrue(script.isFile, "rookproef niet gevonden op ${script.absolutePath}")

        val uitRookproef = Regex("""^fanout\s+"([A-Z]+:\d+)"\s+(\d+)""", RegexOption.MULTILINE)
            .findAll(script.readText())
            .map { it.groupValues[1] to it.groupValues[2].toInt() }
            .toList()

        assertTrue(uitRookproef.isNotEmpty(), "geen fanout-regels herkend in ${script.name}; klopt de vorm nog?")
        assertEquals(uitScript, uitRookproef)
    }

    private fun lees(): List<Pair<String, Int>> {
        val script = File(ROOT, "demo/genereer-magazijnen.py")

        assertTrue(script.isFile, "generatiescript niet gevonden op ${script.absolutePath}")

        val blok = Regex("""ONDERNEMERS\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(script.readText())
            ?.groupValues
            ?.get(1)

        assertTrue(blok != null, "geen ONDERNEMERS-lijst gevonden in ${script.name}")

        val regels = Regex("""\(\s*"[^"]*"\s*,\s*"([A-Z]+)"\s*,\s*"(\d+)"\s*,\s*(\d+)\s*\)""")
            .findAll(blok!!)
            .map { "${it.groupValues[1]}:${it.groupValues[2]}" to it.groupValues[3].toInt() }
            .toList()

        assertTrue(regels.isNotEmpty(), "geen ondernemers herkend in ${script.name}; klopt de vorm nog?")

        return regels
    }

    private companion object {
        /** De module draait vanuit `demo/demo-console`; de repository-wortel ligt twee mappen hoger. */
        val ROOT: File = File("../..").canonicalFile
    }
}
