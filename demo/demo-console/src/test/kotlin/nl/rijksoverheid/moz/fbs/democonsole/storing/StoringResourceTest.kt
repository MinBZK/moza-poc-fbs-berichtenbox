package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.BadRequestException
import nl.rijksoverheid.moz.fbs.democonsole.HERSTELTIJD_MELDING
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

class StoringResourceTest {

    private val storingService = mockk<StoringService>(relaxed = true)
    private val resource = StoringResource(storingService)

    @ParameterizedTest
    @ValueSource(strings = ["profiel", "redis", "notificatie", "aanmeld", "magazijn-a"])
    fun `infraUit geeft de naam onveranderd door aan de service`(proxy: String) {
        every { storingService.uit(proxy) } returns Storingstoestand.UIT

        resource.infraUit(proxy)

        verify { storingService.uit(proxy) }
    }

    @Test
    fun `reset meldt alles normaal pas nadat hij dat teruggelezen heeft`() {
        every { storingService.reset() } returns Unit
        every { storingService.status() } returns mapOf(
            "magazijn-a" to Storingstoestand.NORMAAL,
            "redis" to Storingstoestand.NORMAAL,
        )

        val antwoord = resource.reset()

        assertEquals("alles normaal", antwoord["status"])
        // Het paneel toont deze uitleg onder de melding; zonder dit veld staat de bediener na een
        // reset naar een Berichtenbox te kijken die de organisatie nog als onbereikbaar meldt.
        assertEquals(HERSTELTIJD_MELDING, antwoord["letOp"])
    }

    @ParameterizedTest
    @EnumSource(value = Storingstoestand::class, names = ["TRAAG", "UIT", "ONBEKEND"])
    fun `reset weigert alles normaal te melden zolang een proxy dat niet is`(toestand: Storingstoestand) {
        // De aanroepen naar Toxiproxy slaagden, dus reset() zelf klaagt niet. Zonder terug te lezen
        // schreef de resource hier "alles normaal" op — een groene bevestiging boven een stroom die
        // nog dichtstaat, precies wanneer iemand deze knop indrukt omdat er al iets niet klopt.
        every { storingService.reset() } returns Unit
        every { storingService.status() } returns mapOf(
            "magazijn-a" to Storingstoestand.NORMAAL,
            "profiel" to toestand,
        )

        val fout = assertThrows(IllegalStateException::class.java) { resource.reset() }

        assertTrue(
            fout.message!!.contains("profiel ${toestand.waarde}"),
            "melding moet de proxy en zijn toestand noemen, was: ${fout.message}",
        )
    }

    @Test
    fun `infraUit laat een weigering van het register door`() {
        // Het register is de allowlist; de resource mag die beslissing niet dubbel nemen, want
        // twee lijsten lopen uiteen zodra de configuratie verandert.
        every { storingService.uit("onbekend") } throws BadRequestException("onbekende proxy")

        assertThrows(BadRequestException::class.java) { resource.infraUit("onbekend") }
    }
}
