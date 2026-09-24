package nl.rijksoverheid.moz.fbs.democonsole

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase
import nl.rijksoverheid.moz.fbs.democonsole.sessie.SessieService
import nl.rijksoverheid.moz.fbs.democonsole.simulator.GesimuleerdHerstel
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** De knop "legen": ook de sessies horen weg, anders tonen open berichtenboxen nog de oude berichten. */
class DemoResourceLegenTest {

    private val magazijnDatabase = mockk<MagazijnDatabase>()

    private val simulatorService = mockk<SimulatorService>()

    private val sessieService = mockk<SessieService>()

    private val resource = DemoResource(mockk(), mockk(), mockk(), magazijnDatabase, mockk(), simulatorService, sessieService)

    private fun alleStappenSlagen() {
        every { magazijnDatabase.leegAlles() } returns mapOf("magazijn-a" to 20)
        every { simulatorService.herstelZoMogelijk() } returns GesimuleerdHerstel(berichten = 2000, magazijnen = 98)
        every { sessieService.laatSessiesVerlopenZoMogelijk() } returns 4
    }

    @Test
    fun `legen wist de sessies pas als de magazijnen leeg zijn`() {
        // Andersom haalt een berichtenbox in dat venster de berichten die nog in de magazijnen staan
        // opnieuw op, en blijven die staan tot de sessie verloopt.
        alleStappenSlagen()

        val antwoord = resource.legen()

        verifyOrder {
            magazijnDatabase.leegAlles()
            simulatorService.herstelZoMogelijk()
            sessieService.laatSessiesVerlopenZoMogelijk()
        }

        assertEquals(4, antwoord.sessiesGewist)
        assertEquals(SESSIES_GEWIST_MELDING, antwoord.letOp)
        assertFalse(antwoord.sessiesNietGewist)
    }

    @Test
    fun `een mislukt wissen staat positief op de lijn`() {
        alleStappenSlagen()
        every { sessieService.laatSessiesVerlopenZoMogelijk() } returns null

        val json = jacksonObjectMapper().readTree(jacksonObjectMapper().writeValueAsString(resource.legen()))

        assertTrue(json.path("sessiesNietGewist").booleanValue(), "$json")
        assertEquals(SESSIES_NIET_GEWIST_MELDING, json.path("letOp").asText())
    }
}
