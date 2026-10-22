package nl.rijksoverheid.moz.fbs.democonsole.sessie

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.Path
import nl.rijksoverheid.moz.fbs.democonsole.PaneelBestanden
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class SessieResourceTest {

    private val sessieService = mockk<SessieService>()
    private val resource = SessieResource(sessieService)

    @ParameterizedTest(name = "{0} gewiste keys")
    @ValueSource(ints = [0, 1, 7])
    fun `verlopen meldt het aantal gewiste keys onder gewisteKeys`(aantal: Int) {
        // Nul hoort erbij: bij het eerste legen van een meting is er vaak nog niets te wissen.
        every { sessieService.laatSessiesVerlopen() } returns aantal

        assertEquals(mapOf("gewisteKeys" to aantal), resource.verlopen())
    }

    @Test
    fun `de lezers buiten Kotlin gebruiken hetzelfde veld en hetzelfde pad`() {
        // Die hernoemt geen refactoring mee. Een ander veld laat elke meting stoppen met een melding
        // die naar de inlogmuur wijst en het paneel "undefined" tonen; een ander pad geeft beide
        // een 404.
        every { sessieService.laatSessiesVerlopen() } returns 0

        val pad = SessieResource::class.java.getAnnotation(Path::class.java).value +
            SessieResource::class.java.getMethod("verlopen").getAnnotation(Path::class.java).value
        val meetscript = File(MEETSCRIPT_PAD).readText()

        assertTrue(VELD in resource.verlopen().keys, "het antwoord draagt $VELD niet")
        assertTrue(meetscript.contains("\"$VELD\""), "$MEETSCRIPT_PAD leest $VELD niet")
        assertTrue(meetscript.contains(pad), "$MEETSCRIPT_PAD roept $pad niet aan")
        assertTrue(PaneelBestanden.script().contains("body.$VELD"), "bediening.js toont $VELD niet")
        assertTrue(PaneelBestanden.paneel().contains("data-pad=\"$pad\""), "de knop in index.html roept $pad niet aan")
    }

    private companion object {

        const val MEETSCRIPT_PAD = "../meet-fanout.sh"

        const val VELD = "gewisteKeys"
    }
}
