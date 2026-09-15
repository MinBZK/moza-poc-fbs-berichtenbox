package nl.rijksoverheid.moz.fbs.democonsole

import nl.rijksoverheid.moz.fbs.democonsole.bereikbaarheid.Bereikbaarheidscontrole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * De chip die zegt of de componenten zelf antwoorden. Het paneel is script zonder buildstap, dus
 * deze test leest de bronbestanden, zoals de andere paneeltests.
 */
class PaneelBereikbaarheidTest {

    private val script = PaneelBestanden.script()

    @Test
    fun `de toestandsbalk leest de bereikbaarheid en toont die in een eigen chip`() {
        val verversen = functie("verversToestand")

        assertTrue("lees('/api/demo/bereikbaarheid')" in verversen, "de balk vraagt de bereikbaarheid niet op")
        assertTrue("toonBereikbaarheid(bereikbaarheid);" in verversen, "de balk toont de bereikbaarheid niet")
        assertTrue("""id="chip-bereikbaarheid"""" in PaneelBestanden.paneel(), "de chip ontbreekt in de opmaak")
    }

    @Test
    fun `een bereikbaarheid die niet doorkwam telt mee bij een handmatige bijwerking`() {
        // Anders meldt "Nu bijwerken" groen terwijl de chip ernaast op onbekend staat.
        assertTrue(
            "[status, tempo, storingen, bereikbaarheid].filter(" in functie("verversToestand"),
            "een mislukte bereikbaarheidsuitlezing telt niet mee",
        )
    }

    @Test
    fun `alleen een volledig bereikbare omgeving kleurt de chip groen`() {
        val tonen = functie("toonBereikbaarheid")

        assertEquals(1, Regex("'goed'").findAll(tonen).count(), "groen hoort op precies één plek te staan")
        assertTrue(
            "toestand !== 'bereikbaar'" in tonen,
            "het filter vergelijkt niet op de letterlijke tekst die de console stuurt",
        )
        assertTrue("'fout'" in tonen, "een onbereikbaar component kleurt de chip niet rood")
        // Nul componenten is iets anders dan alles bereikbaar: er wordt dan niets bewaakt.
        assertTrue("'niet ingericht'" in tonen, "een omgeving zonder componenten leest als gezond")
    }

    @Test
    fun `een controleronde past binnen de wachttijd van het paneel`() {
        val wachttijd = Regex("""const LEES_TIMEOUT_MS = (\d+);""").find(script)?.groupValues?.get(1)?.toLong()

        assertTrue(wachttijd != null, "LEES_TIMEOUT_MS niet gevonden in het script")
        // Duurt een ronde langer, dan breekt het paneel de uitlezing af en staat de chip op
        // onbekend — precies wanneer hij had moeten zeggen wélk component hangt.
        assertTrue(
            Bereikbaarheidscontrole.TIMEOUT.toMillis() < wachttijd!!,
            "timeout ${Bereikbaarheidscontrole.TIMEOUT} past niet binnen LEES_TIMEOUT_MS $wachttijd",
        )
    }

    /** Van de declaratie tot aan de volgende functie op het hoogste niveau. */
    private fun functie(naam: String): String {
        val begin = script.indexOf("function $naam(")

        assertTrue(begin >= 0, "functie $naam niet gevonden in het script")

        val einde = Regex("""\n(async )?function """).find(script, begin + 1)?.range?.first ?: script.length

        return script.substring(begin, einde)
    }
}
