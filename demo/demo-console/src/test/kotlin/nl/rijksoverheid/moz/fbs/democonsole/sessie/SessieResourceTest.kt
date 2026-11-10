package nl.rijksoverheid.moz.fbs.democonsole.sessie

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import nl.rijksoverheid.moz.fbs.democonsole.PaneelBestanden
import nl.rijksoverheid.moz.fbs.demopersonas.DemoPersona
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaBron
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class SessieResourceTest {

    private val sessieService = mockk<SessieService>()

    private val beheer = mockk<SessieBeheer>()

    private val personaService = mockk<PersonaService> { every { alle() } returns listOf(VAN_DIJK) }

    private val resource = SessieResource(sessieService, beheer, personaService)

    @ParameterizedTest(name = "{0} sessie(s), {1} gewiste keys")
    @CsvSource("0, 0", "1, 374", "3, 374")
    fun `verlopen meldt de beëindigde sessies naast de gewiste keys`(sessies: Int, keys: Int) {
        // Nul hoort erbij: bij het eerste legen van een meting is er vaak nog niets te wissen.
        every { sessieService.laatSessiesVerlopen() } returns VerlopenSessiecache(gewisteKeys = keys, beeindigdeSessies = sessies)

        assertEquals(VerlopenSessiecache(gewisteKeys = keys, beeindigdeSessies = sessies), resource.verlopen())
    }

    @Test
    fun `de lezers buiten Kotlin gebruiken hetzelfde veld en hetzelfde pad`() {
        // Die hernoemt geen refactoring mee. Een ander veld laat elke meting stoppen met een melding
        // die naar de inlogmuur wijst en het paneel "undefined" tonen; een ander pad geeft beide
        // een 404.
        every { sessieService.laatSessiesVerlopen() } returns VerlopenSessiecache(gewisteKeys = 0, beeindigdeSessies = 0)

        val pad = SessieResource::class.java.getAnnotation(Path::class.java).value +
            SessieResource::class.java.getMethod("verlopen").getAnnotation(Path::class.java).value
        val meetscript = File(MEETSCRIPT_PAD).readText()
        val antwoord = ObjectMapper().valueToTree<JsonNode>(resource.verlopen())

        assertTrue(antwoord.has(VELD), "het antwoord draagt $VELD niet")
        assertTrue(antwoord.has(SESSIES_VELD), "het antwoord draagt $SESSIES_VELD niet")
        assertTrue(meetscript.contains("\"$VELD\""), "$MEETSCRIPT_PAD leest $VELD niet")
        assertTrue(meetscript.contains(pad), "$MEETSCRIPT_PAD roept $pad niet aan")
        assertTrue(PaneelBestanden.script().contains("body.$VELD"), "bediening.js toont $VELD niet")
        assertTrue(PaneelBestanden.script().contains("body.$SESSIES_VELD"), "bediening.js toont $SESSIES_VELD niet")
        assertTrue(PaneelBestanden.paneel().contains("data-pad=\"$pad\""), "de knop in index.html roept $pad niet aan")
    }

    @Test
    fun `lopend geeft het overzicht van de sessiecache door`() {
        val overzicht = Sessieoverzicht(listOf(PersonaSessies("vandijk", emptyList())))

        every { beheer.overzicht() } returns overzicht

        assertEquals(overzicht, resource.lopend())
    }

    @ParameterizedTest(name = "{0} beëindigd")
    @ValueSource(ints = [0, 1])
    fun `een sessie beëindigen meldt hoeveel er beëindigd zijn`(aantal: Int) {
        // Nul is een tweede klik, of een sessie die intussen al verliep: geen fout.
        val verwijzing = "A".repeat(Sessieverwijzer.LENGTE)

        every { beheer.beeindig(verwijzing) } returns aantal

        assertEquals(Beeindigd(aantal), resource.beeindig(verwijzing))
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = ["", "kort", "AAAAAAAAAAAAAAAAAAAAA+", "6a9056ae50ec26106e793b6cb9382d9ad2bcef88ae735fdc802c8206fd50dd3e"])
    fun `een verwijzing van een andere vorm geeft 400 en raakt geen sessie`(verwijzing: String) {
        // Ook een volledige hash: wie een sessie zo aanwijst, hoort geen bevestiging te krijgen
        // dat die vorm werkt.
        assertThrows<BadRequestException> { resource.beeindig(verwijzing) }

        verify(exactly = 0) { beheer.beeindig(any()) }
    }

    @ParameterizedTest(name = "{0} beëindigd")
    @ValueSource(ints = [0, 2])
    fun `alle sessies van een persona beëindigen gaat op de persona-id`(aantal: Int) {
        every { beheer.beeindigVan(VAN_DIJK) } returns aantal

        assertEquals(Beeindigd(aantal), resource.beeindigVanPersona("vandijk"))
    }

    @Test
    fun `zonder persona geeft het 400`() {
        assertThrows<BadRequestException> { resource.beeindigVanPersona("") }

        verify(exactly = 0) { beheer.beeindigVan(any()) }
    }

    @Test
    fun `een onbekende persona geeft 404`() {
        assertThrows<NotFoundException> { resource.beeindigVanPersona("bestaat-niet") }
    }

    @Test
    fun `een persona aangewezen met een nummer geeft 400 zonder het nummer te herhalen`() {
        val fout = assertThrows<BadRequestException> { resource.beeindigVanPersona("90000014") }

        assertTrue("90000014" !in fout.message.orEmpty(), fout.message)
    }

    private companion object {

        const val MEETSCRIPT_PAD = "../meet-fanout.sh"

        const val VELD = "gewisteKeys"

        const val SESSIES_VELD = "beeindigdeSessies"

        val VAN_DIJK = DemoPersona("vandijk", "Garage Van Dijk B.V.", "KVK", "90000014", emptyList(), PersonaBron.KETEN)
    }
}
