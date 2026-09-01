package nl.rijksoverheid.moz.fbs.demopersonas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class PersonaServiceTest {

    @Test
    fun `weigert te starten als er geen persona is ingericht`() {
        val melding = weigering().message!!

        assertTrue(melding.contains("demo.personas"), melding)
    }

    @Test
    fun `levert de enige persona`() {
        val personas = service("pietersen" to VastePersona("J. Pietersen", "BSN", "999993653")).alle()

        assertEquals(listOf("pietersen"), personas.map { it.id })
        assertEquals("BSN:999993653", personas.single().ontvanger)
    }

    @Test
    fun `sorteert op label ongeacht hoofdletters en ongeacht de volgorde in de configuratie`() {
        val personas = service(
            "vandijk" to VastePersona("Garage Van Dijk B.V.", "KVK", "90000014"),
            "pietersen" to VastePersona("J. Pietersen", "BSN", "999993653"),
            "dejong" to VastePersona("de Jong Transport", "KVK", "87654321"),
            "bakkerij" to VastePersona("Bakkerij De Vroege Vogel", "BSN", "999996666"),
        ).alle()

        // Hoofdlettergevoelig sorteren zou "de Jong Transport" achteraan zetten.
        assertEquals(listOf("bakkerij", "dejong", "vandijk", "pietersen"), personas.map { it.id })
    }

    @Test
    fun `houdt bij gelijke labels een vaste volgorde aan`() {
        val personas = service(
            "tweede" to VastePersona("Gelijke Naam B.V.", "KVK", "90000014"),
            "eerste" to VastePersona("Gelijke Naam B.V.", "KVK", "87654321"),
        ).alle()

        assertEquals(listOf("eerste", "tweede"), personas.map { it.id })
    }

    @Test
    fun `noemt de persona-id als een nummer onbruikbaar is`() {
        val melding = weigering("typfout" to VastePersona("Typfout B.V.", "KVK", "1234567")).message!!

        assertTrue(melding.contains("typfout"), melding)
    }


    @Test
    fun `weigert een leeg magazijn-OIN`() {
        val melding = weigering(
            "pietersen" to VastePersona("J. Pietersen", "BSN", "999993653", listOf(TestPersonas.RVO, "")),
        ).message!!

        assertTrue(melding.contains("pietersen"), melding)
    }

    @Test
    fun `weigert een magazijn-OIN met witruimte eromheen, zoals een spatie na de komma oplevert`() {
        val melding = weigering(
            "pietersen" to VastePersona("J. Pietersen", "BSN", "999993653", listOf(" " + TestPersonas.RVO)),
        ).message!!

        assertTrue(melding.contains("witruimte"), melding)
    }

    @Test
    fun `staat hetzelfde nummer toe onder twee verschillende types`() {
        val personas = service(
            "bsn" to VastePersona("A B.V.", "BSN", "999993653"),
            "rsin" to VastePersona("B B.V.", "RSIN", "999993653"),
        ).alle()

        assertEquals(listOf("bsn", "rsin"), personas.map { it.id })
    }

    @Test
    fun `houdt melding en bijgevoegde oorzaken in dezelfde volgorde`() {
        val fout = weigering(
            "zebra" to VastePersona("Zebra B.V.", "KVK", "1234567"),
            "alfa" to VastePersona("Alfa B.V.", "KVK", "7654321"),
        )

        assertTrue(fout.message!!.indexOf("alfa") < fout.message!!.indexOf("zebra"), fout.message)
        assertEquals(listOf("demo-persona 'alfa'", "demo-persona 'zebra'"), fout.suppressed.map { it.message })
    }

    @Test
    fun `noemt het identificatienummer niet in de opstartregel`() {
        val regel = PersonaService.logregel(
            service("pietersen" to VastePersona("J. Pietersen", "BSN", "999993653", listOf(TestPersonas.RVO))).alle(),
        )

        assertTrue(regel.contains("pietersen"), regel)
        assertFalse(regel.contains("999993653"), regel)
    }

    @Test
    fun `weigert twee persona's op hetzelfde identificatienummer`() {
        val melding = weigering(
            "eerste" to VastePersona("Eerste B.V.", "KVK", "90000014"),
            "tweede" to VastePersona("Tweede B.V.", "KVK", "90000014"),
        ).message!!

        assertTrue(melding.contains("eerste") && melding.contains("tweede"), melding)
        assertFalse(melding.contains("12345678"), "het nummer hoort niet in de melding")
    }

    @Test
    fun `weigert hetzelfde magazijn twee keer bij één persona`() {
        weigering(
            "pietersen" to VastePersona("J. Pietersen", "BSN", "999993653", listOf(TestPersonas.RVO, TestPersonas.RVO)),
        )
    }

    @Test
    fun `meldt alle onbruikbare persona's in één keer`() {
        val melding = weigering(
            "eerste" to VastePersona("Eerste B.V.", "KVK", "1234567"),
            "tweede" to VastePersona("Tweede B.V.", "KVK", "7654321"),
        ).message!!

        assertTrue(melding.contains("eerste") && melding.contains("tweede"), melding)
    }



    @Test
    fun `neemt de bron over uit de configuratie`() {
        val personas = service(
            "keten" to VastePersona("A", "KVK", "90000014", listOf(TestPersonas.RVO)),
            "verzonnen" to VastePersona("B", "KVK", "87654321", bron = "dataset"),
        ).alle()

        assertEquals(listOf(PersonaBron.KETEN, PersonaBron.DATASET), personas.map { it.bron })
    }

    @Test
    fun `weigert een dataset-persona die ook ketenberichten zou krijgen`() {
        weigering("mengvorm" to VastePersona("Mengvorm", "KVK", "90000014", listOf(TestPersonas.RVO), "dataset"))
    }

    @Test
    fun `weigert een onbekende bron`() {
        weigering("mock" to VastePersona("Mock", "KVK", "90000014", bron = "mock"))
    }

    @ParameterizedTest
    @MethodSource("optIns")
    fun `alleen persona's met een opt-in krijgen gegenereerde berichten`(magazijnen: List<String>?, verwacht: List<String>) {
        val personas = service(
            "pietersen" to VastePersona("J. Pietersen", "BSN", "999993653", magazijnen),
            "bakkerij" to VastePersona("Bakkerij De Vroege Vogel", "BSN", "999996666", listOf(TestPersonas.BELASTINGDIENST)),
            "grootbedrijf" to VastePersona("Grootbedrijf B.V.", "KVK", "90000001"),
        ).metMagazijnen()

        assertEquals(verwacht, personas.map { it.id })
    }

    private fun service(vararg personas: Pair<String, PersonaConfig.PersonaInstelling>): PersonaService =
        PersonaService(VastePersonaConfig(personas.toMap()))

    /** Toetst dat de inrichting de module laat weigeren te starten, en levert de fout voor verdere assertions. */
    private fun weigering(vararg personas: Pair<String, PersonaConfig.PersonaInstelling>): IllegalArgumentException =
        assertThrows(IllegalArgumentException::class.java) { service(*personas) }

    private companion object {

        @JvmStatic
        fun optIns() = listOf(
            Arguments.of(null, listOf("bakkerij")),
            Arguments.of(emptyList<String>(), listOf("bakkerij")),
            Arguments.of(listOf(TestPersonas.RVO), listOf("bakkerij", "pietersen")),
            Arguments.of(
                listOf(TestPersonas.RVO, TestPersonas.BELASTINGDIENST),
                listOf("bakkerij", "pietersen"),
            ),
        )
    }
}
