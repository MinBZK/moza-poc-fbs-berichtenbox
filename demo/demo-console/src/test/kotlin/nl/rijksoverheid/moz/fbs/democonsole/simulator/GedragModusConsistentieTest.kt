package nl.rijksoverheid.moz.fbs.democonsole.simulator

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `NORMAAL` en `STUK` zijn hier losse strings; aan de andere kant van de lijn zijn het waarden van
 * `GedragModus`. De console heeft geen afhankelijkheid op de simulator, dus de compiler ziet die
 * koppeling niet.
 *
 * Faalscenario zonder deze test: iemand hernoemt `GedragModus.NORMAAL`. `status()` telt dan nul
 * magazijnen als normaal, de chip meldt "0 van de 100 zonder storing" en de scenario-tab krijgt een
 * waarschuwingsstip — terwijl alle honderd gezond antwoorden. Geen enkele test wordt rood.
 *
 * De enum wordt met een reguliere expressie uit de broncode gelezen. Vindt die niets, dan faalt de
 * test: "nul verschillen" is anders niet te onderscheiden van "niets gemeten".
 */
class GedragModusConsistentieTest {

    private val modi: Set<String> = lees()

    @Test
    fun `de modus voor een gezond magazijn bestaat bij de simulator`() {
        assertTrue(SimulatorService.NORMAAL in modi, "${SimulatorService.NORMAAL} staat niet in $modi")
    }

    @Test
    fun `de modus voor een magazijn op storing bestaat bij de simulator`() {
        assertTrue(SimulatorService.STORING in modi, "${SimulatorService.STORING} staat niet in $modi")
    }

    private fun lees(): Set<String> {
        val bron = File(
            "../magazijn-simulator/src/main/kotlin/nl/rijksoverheid/moz/fbs/magazijnsimulator/gedrag/Gedrag.kt",
        )

        assertTrue(bron.isFile, "Gedrag.kt niet gevonden op ${bron.absolutePath}")

        val blok = Regex("""enum class GedragModus\s*\{(.*?)\n}""", RegexOption.DOT_MATCHES_ALL)
            .find(bron.readText())
            ?.groupValues
            ?.get(1)

        assertTrue(blok != null, "geen GedragModus-enum gevonden in ${bron.name}; klopt de vorm nog?")

        val namen = Regex("""^\s{4}([A-Z_]+),""", RegexOption.MULTILINE)
            .findAll(blok!!)
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(namen.isNotEmpty(), "geen modi herkend in ${bron.name}; klopt de vorm nog?")

        return namen
    }
}
