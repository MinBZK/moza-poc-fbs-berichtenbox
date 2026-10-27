package nl.rijksoverheid.moz.fbs.berichtenapp

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

/**
 * De tests van de browsercode (de `.test.mjs`-bestanden in `src/test/js`) onder `node --test`,
 * zodat ze met elke Maven-build meedraaien. Zonder Node faalt deze test: overslaan zou een groene
 * build zonder getoetste browsercode opleveren. Om dezelfde reden telt hij de geslaagde tests.
 */
class JavascriptTest {

    @Test
    fun `de tests van de browsercode slagen onder Node`() {
        val bestanden = Files.list(Path.of("src/test/js")).use { stream ->
            stream.filter { it.name.endsWith(".test.mjs") }.map(Path::toString).sorted().toList()
        }

        assertTrue(bestanden.isNotEmpty(), "geen JS-tests gevonden")

        val uitvoer = node(listOf("--test", "--test-reporter=tap") + bestanden)
        val geslaagd = Regex("^# pass (\\d+)$", RegexOption.MULTILINE).find(uitvoer)?.groupValues?.get(1)?.toInt() ?: 0

        assertTrue(geslaagd >= bestanden.size, "te weinig geslaagde JS-tests ($geslaagd):\n$uitvoer")
    }

    private fun node(argumenten: List<String>): String {
        val log = Files.createTempFile("node", ".log")

        // Naar een bestand, niet naar een pipe: een volle pipe-buffer laat node blokkeren tot de
        // tijdslimiet afloopt.
        val proces = ProcessBuilder(listOf("node") + argumenten).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val klaar = proces.waitFor(TIJDSLIMIET_SECONDEN, TimeUnit.SECONDS)

        if (!klaar) proces.destroyForcibly()

        val uitvoer = Files.readString(log)

        Files.delete(log)

        assertTrue(klaar && proces.exitValue() == 0, "node faalde:\n$uitvoer")

        return uitvoer
    }

    private companion object {
        const val TIJDSLIMIET_SECONDEN = 120L
    }
}
