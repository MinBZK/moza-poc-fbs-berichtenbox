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

    /**
     * Op `2..500` en niet op `1..500`: de cijfers van de ondergrens mogen niet in de testwaarden
     * voorkomen, anders houdt `contains` de echo van de invoer voor de grens en bewaakt hij niets.
     */
    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "-500", "501"])
    fun `een waarde buiten de grenzen wordt geweigerd met beide grenzen erbij`(waarde: String) {
        val fout = assertThrows<BadRequestException> { heelGetal("aantal", waarde, standaard = 10, grenzen = 2..500) }

        assertTrue(fout.message!!.contains("aantal"), "de melding hoort het veld te noemen: ${fout.message}")
        assertTrue(fout.message!!.contains("2 en 500"), "de melding hoort beide grenzen te noemen: ${fout.message}")
    }

    /**
     * `3000000000` past niet in een `Int` en `1.5` is geen geheel getal; beide leveren zonder eigen
     * afhandeling een omzettingsfout op die JAX-RS met 404 beantwoordt.
     */
    @ParameterizedTest
    @ValueSource(strings = ["abc", "1.5", "3000000000", "1e3", "10 ", " 10"])
    fun `een onleesbare waarde wordt geweigerd met dezelfde melding`(waarde: String) {
        val fout = assertThrows<BadRequestException> { heelGetal("aantal", waarde, standaard = 10, grenzen = 2..500) }

        assertTrue(fout.message!!.contains("aantal"), "de melding hoort het veld te noemen: ${fout.message}")
        assertTrue(fout.message!!.contains("2 en 500"), "de melding hoort beide grenzen te noemen: ${fout.message}")
    }

    @Test
    fun `de melding noemt het veld waar de waarde vandaan komt`() {
        // Drie velden van het paneel gaan hier doorheen, onder twee namen; een vaste tekst "aantal"
        // stuurt de bediener bij een verkeerd interval naar het verkeerde veld.
        val fout = assertThrows<BadRequestException> { heelGetal("interval", "0", standaard = 10, grenzen = 1..3600) }

        assertTrue(fout.message!!.contains("interval"), "de melding hoort het veld te noemen: ${fout.message}")
        assertTrue("aantal" !in fout.message!!, "de veldnaam hoort niet vast te staan: ${fout.message}")
    }

    @Test
    fun `de melding draagt de eenheid van de grens`() {
        // "tussen 1 en 3600" leest zonder eenheid net zo goed als milliseconden of als een aantal
        // berichten. De melding van TempoService noemde seconden; via HTTP is die nu onbereikbaar.
        val fout = assertThrows<BadRequestException> {
            heelGetal("interval", "5000", standaard = 10, grenzen = 1..3600, eenheid = "seconden")
        }

        assertTrue(fout.message!!.contains("3600 seconden"), "de eenheid hoort bij de grens: ${fout.message}")
    }

    /**
     * De echo van de invoer gaat via `DemoFoutMapper` ook de applicatielog in. Een newline zou daar
     * een tweede regel schrijven die als een echte gebeurtenis leest, en een lange waarde zou de log
     * van een gedeelde omgeving vol kunnen schrijven.
     */
    @ParameterizedTest
    @ValueSource(strings = ["a\nINFO herstel voltooid", "a\rb", "a\tb"])
    fun `de echo van een onleesbare waarde draagt geen regeleindes`(waarde: String) {
        val fout = assertThrows<BadRequestException> { heelGetal("aantal", waarde, standaard = 10, grenzen = 1..500) }

        assertTrue(fout.message!!.lines().size == 1, "de melding hoort één regel te zijn: ${fout.message}")
        assertTrue('\t' !in fout.message!!, "de melding hoort geen tabs te dragen: ${fout.message}")
    }

    @Test
    fun `de echo van een onleesbare waarde wordt afgekapt`() {
        val fout = assertThrows<BadRequestException> { heelGetal("aantal", "9".repeat(200), standaard = 10, grenzen = 1..500) }

        assertTrue(fout.message!!.length < 100, "de melding hoort begrensd te zijn: ${fout.message!!.length} tekens")
    }

    /**
     * De functie bestaat om een grens af te dwingen, maar de terugvalwaarde ging er ongetoetst
     * langs: `standaard = 0` op `1..500` leverde bij een lege parameter precies de nul op die de
     * rest van de functie weigert. Een `require` en geen `BadRequestException`: dit is een fout van
     * de aanroeper, geen bedieningsfout, dus een 500 is hier de juiste uitkomst.
     */
    @Test
    fun `een standaard buiten de grenzen is een programmeerfout`() {
        assertThrows<IllegalArgumentException> { heelGetal("aantal", "", standaard = 0, grenzen = 1..500) }
    }
}
