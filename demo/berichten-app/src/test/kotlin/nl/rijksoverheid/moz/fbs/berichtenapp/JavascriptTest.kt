package nl.rijksoverheid.moz.fbs.berichtenapp

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

/**
 * De tests van de browsercode (de `.test.mjs`-bestanden in `src/test/js`) onder `node --test`,
 * zodat ze met elke Maven-build meedraaien. Zonder Node faalt deze test: overslaan zou een groene
 * build zonder getoetste browsercode opleveren. Om dezelfde reden moet elk bestand tests bevatten
 * en moeten er minstens zoveel slagen als er in de bestanden staan: een bestand dat niet laadt,
 * telt bij Node als één mislukte test en verdwijnt anders achter de geslaagde van de rest.
 */
class JavascriptTest {

    @Test
    fun `de tests van de browsercode slagen onder Node`() {
        val bestanden = Files.list(Path.of("src/test/js")).use { stream ->
            stream.filter { it.name.endsWith(".test.mjs") }.sorted().toList()
        }

        assertTrue(bestanden.isNotEmpty(), "geen JS-tests gevonden")

        val perBestand = bestanden.associate { it.name to TEST_AANROEP.findAll(Files.readString(it)).count() }

        assertTrue(perBestand.values.all { it > 0 }, "JS-testbestanden zonder test: $perBestand")

        // De hook laat de browsercode jose uit node_modules laden; de build zet hem pas in target/.
        val uitvoer = node(listOf("--import", "./src/test/js/jose-uit-npm.mjs", "--test", "--test-reporter=tap") + bestanden.map(Path::toString))
        val geslaagd = telling(uitvoer, "pass")

        assertTrue(geslaagd >= perBestand.values.sum(), "te weinig geslaagde JS-tests ($geslaagd, verwacht $perBestand):\n$uitvoer")
        assertTrue(NIET_GEDRAAID.all { telling(uitvoer, it) == 0 }, "niet alle JS-tests zijn gedraaid:\n$uitvoer")
    }

    /** Ontbreekt de regel, dan is de samenvatting van Node veranderd en zegt een nul niets. */
    private fun telling(uitvoer: String, soort: String): Int {
        val regel = Regex("^# $soort (\\d+)$", RegexOption.MULTILINE).find(uitvoer)

        assertNotNull(regel, "geen regel '# $soort' in de uitvoer van node:\n$uitvoer")

        return regel!!.groupValues[1].toInt()
    }

    private fun node(argumenten: List<String>): String {
        val log = Files.createTempFile("node", ".log")

        // Naar een bestand, niet naar een pipe: een volle pipe-buffer laat node blokkeren tot de
        // tijdslimiet afloopt.
        val proces = ProcessBuilder(listOf("node") + argumenten).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val klaar = proces.waitFor(TIJDSLIMIET_SECONDEN, TimeUnit.SECONDS)

        // `node --test` start per bestand een eigen proces; die moeten mee, anders blijven ze draaien.
        if (!klaar) {
            proces.descendants().forEach { it.destroyForcibly() }
            proces.destroyForcibly()
        }

        val uitvoer = Files.readString(log)

        Files.delete(log)

        assertTrue(klaar && proces.exitValue() == 0, "node faalde:\n$uitvoer")

        return uitvoer
    }

    private companion object {
        const val TIJDSLIMIET_SECONDEN = 120L

        // Ook de tests die een lus maakt, beginnen met `test(` aan het begin van een regel.
        val TEST_AANROEP = Regex("^\\s*test\\(", RegexOption.MULTILINE)

        val NIET_GEDRAAID = listOf("fail", "cancelled", "skipped", "todo")
    }
}
