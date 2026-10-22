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
        // Nul hoort erbij: ook een lege sessiecache moet de meting als bevestigd legen herkennen.
        every { sessieService.laatSessiesVerlopen() } returns aantal

        assertEquals(mapOf("gewisteKeys" to aantal), resource.verlopen())
    }

    @Test
    fun `de lezers buiten Kotlin gebruiken hetzelfde veld en hetzelfde pad`() {
        // Die twee hernoemt geen refactoring mee. Zonder deze toets stopt elke meting na een
        // hernoeming met een melding die naar de inlogmuur wijst, en toont het paneel "undefined".
        every { sessieService.laatSessiesVerlopen() } returns 0

        val veld = resource.verlopen().keys.single()
        val pad = SessieResource::class.java.getAnnotation(Path::class.java).value +
            SessieResource::class.java.getMethod("verlopen").getAnnotation(Path::class.java).value
        val meetscript = File(MEETSCRIPT_PAD).readText()

        assertTrue(meetscript.contains("\"$veld\""), "$MEETSCRIPT_PAD leest $veld niet")
        assertTrue(meetscript.contains(pad), "$MEETSCRIPT_PAD roept $pad niet aan")
        assertTrue(PaneelBestanden.script().contains("body.$veld"), "bediening.js toont $veld niet")
    }

    private companion object {

        const val MEETSCRIPT_PAD = "../meet-fanout.sh"
    }
}
