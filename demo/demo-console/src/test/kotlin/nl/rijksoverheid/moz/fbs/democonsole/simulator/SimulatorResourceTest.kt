package nl.rijksoverheid.moz.fbs.democonsole.simulator

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.BadRequestException
import nl.rijksoverheid.moz.fbs.democonsole.HERSTELTIJD_MELDING
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * De laag tussen de knoppen en de service. Weinig logica, maar wel drie beslissingen die stil kunnen
 * breken: de defaults van de vul-knop, de vertaling van een ongeldige `k` naar een 400, en de vorm
 * van het legen-antwoord.
 */
class SimulatorResourceTest {

    private val service = mockk<SimulatorService>()
    private val resource = SimulatorResource(service)

    @Test
    fun `vullen zonder invoer gebruikt de vastgelegde standaardwaarden`() {
        // De velden zijn optioneel in het paneel; vielen de defaults weg, dan zette de knop nul
        // berichten klaar en leek de demo leeg zonder dat iets rood werd.
        every { service.vul(any(), any(), any()) } returns SeedUitkomst(98, 4, 10584, 2646, 0, 500)

        resource.vullen(null, null)

        verify {
            service.vul(
                SimulatorService.ONDERNEMERS,
                SimulatorService.STANDAARD_PER_MAGAZIJN,
                SimulatorService.STANDAARD_BIJLAGE_ELKE,
            )
        }
    }

    @Test
    fun `vullen geeft ingevulde waarden onveranderd door`() {
        every { service.vul(any(), any(), any()) } returns SeedUitkomst(98, 4, 490, 98, 0, 120)

        resource.vullen(5, 2)

        verify { service.vul(SimulatorService.ONDERNEMERS, 5, 2) }
    }

    @Test
    fun `een k buiten het bereik wordt een 400 en geen 500`() {
        // Het is invoer van de bediener, geen storing in de keten; een 500 zou hem in de logs laten
        // zoeken naar een fout die hij zelf net intypte.
        every { service.zetActief(any()) } throws IllegalArgumentException("k moet tussen 0 en 5 liggen")

        val fout = assertThrows(BadRequestException::class.java) { resource.zetActief(9) }

        assertEquals("k moet tussen 0 en 5 liggen", fout.message)
    }

    @Test
    fun `zetActief geeft de stand van de simulator terug`() {
        every { service.zetActief(3) } returns SimulatorStand(actief = 3, totaal = 12)

        assertEquals(SimulatorStand(actief = 3, totaal = 12), resource.zetActief(3))
    }

    @Test
    fun `legen draagt de tellingen plus de uitleg over de hersteltijd`() {
        // De melding hoort in het antwoord: het paneel toont de uitkomst van een knop, en dát is het
        // moment waarop iemand kijkt.
        every { service.herstel() } returns GesimuleerdHerstel(berichten = 2000, magazijnen = 98)

        assertEquals(LegenAntwoord(berichten = 2000, magazijnen = 98, letOp = HERSTELTIJD_MELDING), resource.legen())
    }

    @Test
    fun `legen laat een onbereikbare simulator gewoon falen`() {
        // Deze knop mikt expliciet op de simulator; "0 berichten weg" melden zou hier een leugen
        // zijn in plaats van een overgeslagen deelstap.
        every { service.herstel() } throws IllegalStateException("simulator onbereikbaar")

        assertThrows(IllegalStateException::class.java) { resource.legen() }
    }
}
