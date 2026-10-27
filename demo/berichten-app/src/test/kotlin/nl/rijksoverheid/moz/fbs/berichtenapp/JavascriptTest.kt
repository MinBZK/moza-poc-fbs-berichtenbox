package nl.rijksoverheid.moz.fbs.berichtenapp

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

/**
 * De tests van de browsercode (de `.test.mjs`-bestanden in `src/test/js`) onder `node --test`, zodat ze met elke
 * Maven-build meedraaien. Zonder Node faalt deze test: overslaan zou een groene build zonder
 * getoetste browsercode opleveren.
 */
class JavascriptTest {

    @Test
    fun `de tests van de browsercode slagen onder Node`() {
        val bestanden = Files.list(Path.of("src/test/js")).use { stroom ->
            stroom.filter { it.name.endsWith(".test.mjs") }.map(Path::toString).sorted().toList()
        }

        assertTrue(bestanden.isNotEmpty(), "geen JS-tests gevonden")

        val proces = ProcessBuilder(listOf("node", "--test") + bestanden).redirectErrorStream(true).start()
        val klaar = proces.waitFor(TIJDSLIMIET_SECONDEN, TimeUnit.SECONDS)
        val uitvoer = proces.inputStream.bufferedReader().readText()

        println(uitvoer)

        assertTrue(klaar && proces.exitValue() == 0, "node --test faalde:\n$uitvoer")
    }

    private companion object {
        const val TIJDSLIMIET_SECONDEN = 120L
    }
}
