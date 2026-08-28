package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * De fail-fast op de geconfigureerde set. De uitvraag krijgt zijn register uit hetzelfde
 * generator-artefact, dus een fout die de boot passeert levert pas bij het eerste verkeer een 404
 * op — midden in een demo, bij één van de honderd magazijnen, met de oorzaak ver weg.
 */
class MagazijnConfiguratieTest {

    @Test
    fun `een lege set blokkeert de boot`() {
        val fout = assertThrows<IllegalStateException> { valideer() }

        assertEquals(true, fout.message?.contains("Geen magazijnen geconfigureerd"))
    }

    @Test
    fun `één magazijn levert één regel op`() {
        assertEquals(mapOf(EEN to "Demo-magazijn 1"), valideer(EEN to "Demo-magazijn 1"))
    }

    /**
     * Meerdere naast elkaar, want met één regel ziet een implementatie die alles op één hoop gooit
     * er precies zo uit als een die per OIN bijhoudt.
     */
    @Test
    fun `meerdere magazijnen houden elk hun eigen naam`() {
        assertEquals(
            mapOf(EEN to "Demo-magazijn 1", TWEE to "Demo-magazijn 2", DRIE to "Demo-magazijn 3"),
            valideer(EEN to "Demo-magazijn 1", TWEE to "Demo-magazijn 2", DRIE to "Demo-magazijn 3"),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "1234567890123456789",
            "123456789012345678901",
            "0000000900000000000a",
            "0000000900000000 001",
            "",
            "00000000000000000000",
        ],
    )
    fun `een key die geen OIN is blokkeert de boot`(key: String) {
        assertThrows<IllegalStateException> { valideer(key to "Demo-magazijn") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\t"])
    fun `een lege naam blokkeert de boot`(naam: String) {
        assertThrows<IllegalStateException> { valideer(EEN to naam) }
    }

    @Test
    fun `omringende whitespace in de naam wordt weggehaald`() {
        assertEquals(mapOf(EEN to "Demo-magazijn 1"), valideer(EEN to "  Demo-magazijn 1  "))
    }

    private fun valideer(vararg entries: Pair<String, String>): Map<String, String> =
        MagazijnConfiguratie.valideer(
            entries.toMap().mapValues { (_, waarde) ->
                object : MagazijnSimulatorConfig.Inschrijving {
                    override fun naam(): String = waarde
                }
            },
        )

    private companion object {
        const val EEN = "00000009000000000001"
        const val TWEE = "00000009000000000002"
        const val DRIE = "00000009000000000003"
    }
}
