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

    private val ondernemersUitScript: List<String> = lees()

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
        val personas = TestPersonas.uitApplicationProperties().alle().map { it.ontvanger }.toSet()

        SimulatorService.ONDERNEMERS.forEach { ondernemer ->
            assertTrue(ondernemer in personas, "$ondernemer staat niet in demo.personas.*")
        }
    }

    private fun lees(): List<String> {
        val script = File(WORTEL, "demo/genereer-magazijnen.py")

        assertTrue(script.isFile, "generatiescript niet gevonden op ${script.absolutePath}")

        val blok = Regex("""ONDERNEMERS\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(script.readText())
            ?.groupValues
            ?.get(1)

        assertTrue(blok != null, "geen ONDERNEMERS-lijst gevonden in ${script.name}")

        val regels = Regex("""\(\s*"[^"]*"\s*,\s*"([A-Z]+)"\s*,\s*"(\d+)"\s*,\s*(\d+)\s*\)""")
            .findAll(blok!!)
            .map { "${it.groupValues[1]}:${it.groupValues[2]}" }
            .toList()

        assertTrue(regels.isNotEmpty(), "geen ondernemers herkend in ${script.name}; klopt de vorm nog?")

        return regels
    }

    private companion object {
        /** De module draait vanuit `demo/demo-console`; de repository-wortel ligt twee mappen hoger. */
        val WORTEL: File = File("../..").canonicalFile
    }
}
