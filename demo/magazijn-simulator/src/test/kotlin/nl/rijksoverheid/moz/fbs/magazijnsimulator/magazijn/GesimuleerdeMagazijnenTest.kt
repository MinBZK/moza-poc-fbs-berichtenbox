package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * De set die de simulator bij het starten inleest. Twee dingen worden hier vastgepind.
 *
 * De **fail-fast op de configuratie**: de uitvraag krijgt zijn register uit hetzelfde
 * generator-artefact, dus een fout die de boot passeert levert pas bij het eerste verkeer een 404
 * op — midden in een demo, bij één van de honderd magazijnen.
 *
 * En dat het opzoeken écht **per sleutel discrimineert**. Met één ingeschreven magazijn ziet een
 * implementatie die altijd het eerste teruggeeft er precies zo uit als een die de sleutel gebruikt,
 * dus de cardinaliteiten leeg / één / meerdere staan er alle drie in.
 */
class GesimuleerdeMagazijnenTest {

    @Test
    fun `een lege set blokkeert de boot`() {
        val fout = assertThrows<IllegalArgumentException> { magazijnenVan() }

        assertEquals(true, fout.message?.contains("Geen magazijnen geconfigureerd"))
    }

    @Test
    fun `één magazijn is te vinden op zijn eigen OIN`() {
        val set = magazijnenVan(EEN to "Demo-magazijn 1")

        assertEquals(GesimuleerdMagazijn(EEN, "Demo-magazijn 1"), set.voorOin(EEN))
        assertNull(set.voorOin(TWEE))
    }

    @Test
    fun `bij meerdere magazijnen levert elke OIN zijn eigen magazijn op`() {
        val set = magazijnenVan(
            EEN to "Demo-magazijn 1",
            TWEE to "Demo-magazijn 2",
            DRIE to "Demo-magazijn 3",
        )

        assertEquals(GesimuleerdMagazijn(EEN, "Demo-magazijn 1"), set.voorOin(EEN))
        assertEquals(GesimuleerdMagazijn(TWEE, "Demo-magazijn 2"), set.voorOin(TWEE))
        assertEquals(GesimuleerdMagazijn(DRIE, "Demo-magazijn 3"), set.voorOin(DRIE))
    }

    @Test
    fun `een OIN die niet is ingeschreven levert niets op, ook niet het eerste magazijn`() {
        val set = magazijnenVan(EEN to "Demo-magazijn 1", TWEE to "Demo-magazijn 2")

        assertNull(set.voorOin("00000009000000009999"))
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
        assertThrows<IllegalStateException> { magazijnenVan(key to "Demo-magazijn") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\t"])
    fun `een lege naam blokkeert de boot`(naam: String) {
        assertThrows<IllegalStateException> { magazijnenVan(EEN to naam) }
    }

    @Test
    fun `omringende whitespace in de naam wordt weggehaald`() {
        val set = magazijnenVan(EEN to "  Demo-magazijn 1  ")

        assertEquals("Demo-magazijn 1", set.voorOin(EEN)?.naam)
    }

    private fun magazijnenVan(vararg entries: Pair<String, String>): GesimuleerdeMagazijnen =
        GesimuleerdeMagazijnen(VasteConfig(entries.toMap())).apply { init() }

    private class VasteConfig(private val entries: Map<String, String>) : MagazijnSimulatorConfig {
        override fun magazijnen(): Map<String, MagazijnSimulatorConfig.Inschrijving> =
            entries.mapValues { (_, waarde) ->
                object : MagazijnSimulatorConfig.Inschrijving {
                    override fun naam(): String = waarde
                }
            }
    }

    private companion object {
        const val EEN = "00000009000000000001"
        const val TWEE = "00000009000000000002"
        const val DRIE = "00000009000000000003"
    }
}
