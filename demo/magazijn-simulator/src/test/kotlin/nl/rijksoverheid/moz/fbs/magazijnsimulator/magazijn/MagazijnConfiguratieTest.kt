package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.Gedrag
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.GedragModus
import org.junit.jupiter.params.provider.ValueSource
import java.util.Optional
import java.util.OptionalInt

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
        assertEquals(mapOf(EEN to normaal("Demo-magazijn 1")), valideer(EEN to "Demo-magazijn 1"))
    }

    /**
     * Meerdere naast elkaar, want met één regel ziet een implementatie die alles op één hoop gooit
     * er precies zo uit als een die per OIN bijhoudt.
     */
    @Test
    fun `meerdere magazijnen houden elk hun eigen naam`() {
        assertEquals(
            mapOf(
                EEN to normaal("Demo-magazijn 1"),
                TWEE to normaal("Demo-magazijn 2"),
                DRIE to normaal("Demo-magazijn 3"),
            ),
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
        assertEquals(mapOf(EEN to normaal("Demo-magazijn 1")), valideer(EEN to "  Demo-magazijn 1  "))
    }

    /**
     * Zonder volgnummer gedraagt een magazijn zich normaal: het staat dan niet in de gegenereerde
     * verdeling, en iets anders verzinnen zou een storing opleveren die niemand heeft ingesteld.
     */
    @Test
    fun `zonder volgnummer is het gedrag normaal`() {
        assertEquals(Gedrag.NORMAAL, valideer(EEN to "Demo-magazijn 1")[EEN]?.gedrag)
    }

    @Test
    fun `het volgnummer bepaalt het gedrag via de vastgelegde verdeling`() {
        assertEquals(GedragModus.TRAAG, valideerMetIndex(EEN, 5)[EEN]?.gedrag?.modus)
        assertEquals(GedragModus.UIT, valideerMetIndex(EEN, 28)[EEN]?.gedrag?.modus)
        assertEquals(GedragModus.NORMAAL, valideerMetIndex(EEN, 1)[EEN]?.gedrag?.modus)
    }

    @Test
    fun `een expliciet gedrag wint van het volgnummer`() {
        assertEquals(GedragModus.STUK, valideerMetIndex(EEN, 5, gedrag = "STUK")[EEN]?.gedrag?.modus)
    }

    @ParameterizedTest
    @ValueSource(strings = ["traag", "  TRAAG  "])
    fun `een gedrag mag slordig geschreven zijn`(gedrag: String) {
        assertEquals(GedragModus.TRAAG, valideerMetIndex(EEN, 1, gedrag = gedrag)[EEN]?.gedrag?.modus)
    }

    @Test
    fun `een onbekend gedrag blokkeert de boot in plaats van stil normaal te worden`() {
        val fout = assertThrows<IllegalStateException> { valideerMetIndex(EEN, 1, gedrag = "SOMS") }

        assertEquals(true, fout.message?.contains("SOMS"))
    }

    @Test
    fun `een volgnummer onder één blokkeert de boot`() {
        assertThrows<IllegalStateException> { valideerMetIndex(EEN, 0) }
    }

    private fun normaal(naam: String) = MagazijnInstelling(naam, Gedrag.NORMAAL)

    private fun valideer(vararg entries: Pair<String, String>): Map<String, MagazijnInstelling> =
        MagazijnConfiguratie.valideer(
            entries.toMap().mapValues { (_, waarde) -> inschrijving(waarde) },
        )

    private fun valideerMetIndex(oin: String, index: Int, gedrag: String? = null) =
        MagazijnConfiguratie.valideer(mapOf(oin to inschrijving("Demo-magazijn", index, gedrag)))

    private fun inschrijving(waarde: String, index: Int? = null, gedrag: String? = null) =
        object : MagazijnSimulatorConfig.Inschrijving {
            override fun naam(): String = waarde
            override fun index(): OptionalInt = index?.let { OptionalInt.of(it) } ?: OptionalInt.empty()
            override fun gedrag(): Optional<String> = Optional.ofNullable(gedrag)
        }

    private companion object {
        const val EEN = "00000009000000000001"
        const val TWEE = "00000009000000000002"
        const val DRIE = "00000009000000000003"
    }
}
