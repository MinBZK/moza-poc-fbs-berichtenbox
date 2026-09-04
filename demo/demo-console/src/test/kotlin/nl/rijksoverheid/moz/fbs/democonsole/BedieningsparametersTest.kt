package nl.rijksoverheid.moz.fbs.democonsole

import jakarta.ws.rs.BadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * De lezer achter elk aantal- en intervalveld van het paneel. Wat hij weigert bepaalt of een
 * bediener een leesbare melding krijgt of een ronde die niets deed.
 */
class BedieningsparametersTest {

    @Test
    fun `een waarde binnen de grenzen komt ongewijzigd terug`() {
        assertEquals(7, heelGetal("aantal", "7", standaard = 10, grenzen = 1..500))
    }

    @ParameterizedTest
    @ValueSource(strings = ["1", "500"])
    fun `de grenzen zelf zijn toegestaan`(waarde: String) {
        // Anders weigert de server precies de bovengrens die het invoerveld aanbiedt.
        assertEquals(waarde.toInt(), heelGetal("aantal", waarde, standaard = 10, grenzen = 1..500))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `een lege waarde telt als niet opgegeven`(waarde: String) {
        assertEquals(10, heelGetal("aantal", waarde, standaard = 10, grenzen = 1..500))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "-500", "501"])
    fun `een waarde buiten de grenzen wordt geweigerd met de grenzen erbij`(waarde: String) {
        val fout = assertThrows<BadRequestException> { heelGetal("aantal", waarde, standaard = 10, grenzen = 1..500) }

        assertTrue(fout.message!!.contains("aantal"), "de melding hoort het veld te noemen: ${fout.message}")
        assertTrue(fout.message!!.contains("1"), "de melding hoort de ondergrens te noemen: ${fout.message}")
        assertTrue(fout.message!!.contains("500"), "de melding hoort de bovengrens te noemen: ${fout.message}")
    }

    /**
     * `3000000000` past niet in een `Int` en `1.5` is geen geheel getal; beide leveren zonder eigen
     * afhandeling een omzettingsfout op die JAX-RS met 404 beantwoordt.
     */
    @ParameterizedTest
    @ValueSource(strings = ["abc", "1.5", "3000000000", "1e3", "10 ", " 10"])
    fun `een onleesbare waarde wordt geweigerd met dezelfde melding`(waarde: String) {
        val fout = assertThrows<BadRequestException> { heelGetal("aantal", waarde, standaard = 10, grenzen = 1..500) }

        assertTrue(fout.message!!.contains("aantal"), "de melding hoort het veld te noemen: ${fout.message}")
        assertTrue(fout.message!!.contains("500"), "de melding hoort de grenzen te noemen: ${fout.message}")
    }

    @Test
    fun `de melding noemt het veld waar de waarde vandaan komt`() {
        // Twee velden van het paneel gaan hier doorheen; een vaste tekst "aantal" stuurt de bediener
        // bij een verkeerd interval naar het verkeerde veld.
        val fout = assertThrows<BadRequestException> { heelGetal("interval", "0", standaard = 10, grenzen = 1..3600) }

        assertTrue(fout.message!!.contains("interval"), "de melding hoort het veld te noemen: ${fout.message}")
        assertTrue("aantal" !in fout.message!!, "de veldnaam hoort niet vast te staan: ${fout.message}")
    }
}
