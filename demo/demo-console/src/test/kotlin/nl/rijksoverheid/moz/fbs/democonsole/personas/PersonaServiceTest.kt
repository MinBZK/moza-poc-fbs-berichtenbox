package nl.rijksoverheid.moz.fbs.democonsole.personas

import nl.rijksoverheid.moz.fbs.democonsole.DemoConfig
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
        val fout = assertThrows(IllegalArgumentException::class.java) { service(emptyMap()) }

        assertTrue(fout.message!!.contains("demo.personas"), fout.message)
    }

    @Test
    fun `levert de enige persona`() {
        val personas = service(mapOf("pietersen" to instelling("J. Pietersen", "BSN", "999993653"))).alle()

        assertEquals(listOf("pietersen"), personas.map { it.id })
        assertEquals("BSN:999993653", personas.single().ontvanger)
    }

    @Test
    fun `sorteert op label ongeacht hoofdletters en ongeacht de volgorde in de configuratie`() {
        val personas = service(
            mapOf(
                "vandijk" to instelling("Garage Van Dijk B.V.", "KVK", "12345678"),
                "pietersen" to instelling("J. Pietersen", "BSN", "999993653"),
                "dejong" to instelling("de Jong Transport", "KVK", "87654321"),
                "bakkerij" to instelling("Bakkerij De Vroege Vogel", "BSN", "999996666"),
            ),
        ).alle()

        // Hoofdlettergevoelig sorteren zou "de Jong Transport" achteraan zetten.
        assertEquals(listOf("bakkerij", "dejong", "vandijk", "pietersen"), personas.map { it.id })
    }

    @Test
    fun `houdt bij gelijke labels een vaste volgorde aan`() {
        val personas = service(
            mapOf(
                "tweede" to instelling("Gelijke Naam B.V.", "KVK", "12345678"),
                "eerste" to instelling("Gelijke Naam B.V.", "KVK", "87654321"),
            ),
        ).alle()

        assertEquals(listOf("eerste", "tweede"), personas.map { it.id })
    }

    @Test
    fun `noemt de persona-id als een nummer onbruikbaar is`() {
        val fout = assertThrows(IllegalArgumentException::class.java) {
            service(mapOf("typfout" to instelling("Typfout B.V.", "KVK", "1234567")))
        }

        assertTrue(fout.message!!.contains("typfout"), fout.message)
    }

    @Test
    fun `weigert een opt-in op een magazijn zonder aanlever-URL`() {
        val fout = assertThrows(IllegalArgumentException::class.java) {
            service(mapOf("pietersen" to instelling("J. Pietersen", "BSN", "999993653", listOf("00000000000000999999"))))
        }

        assertTrue(fout.message!!.contains("00000000000000999999"), fout.message)
        assertTrue(fout.message!!.contains("pietersen"), fout.message)
    }

    @Test
    fun `weigert een leeg magazijn-OIN, zoals een afsluitende komma oplevert`() {
        val fout = assertThrows(IllegalArgumentException::class.java) {
            service(mapOf("pietersen" to instelling("J. Pietersen", "BSN", "999993653", listOf(TestPersonas.RVO, ""))))
        }

        assertTrue(fout.message!!.contains("komma"), fout.message)
    }

    @Test
    fun `weigert twee persona's op hetzelfde identificatienummer`() {
        val fout = assertThrows(IllegalArgumentException::class.java) {
            service(
                mapOf(
                    "eerste" to instelling("Eerste B.V.", "KVK", "12345678"),
                    "tweede" to instelling("Tweede B.V.", "KVK", "12345678"),
                ),
            )
        }

        assertTrue(fout.message!!.contains("eerste") && fout.message!!.contains("tweede"), fout.message)
        assertFalse(fout.message!!.contains("12345678"), "het nummer hoort niet in de melding")
    }

    @Test
    fun `weigert hetzelfde magazijn twee keer bij één persona`() {
        assertThrows(IllegalArgumentException::class.java) {
            service(
                mapOf(
                    "pietersen" to instelling("J. Pietersen", "BSN", "999993653", listOf(TestPersonas.RVO, TestPersonas.RVO)),
                ),
            )
        }
    }

    @Test
    fun `meldt alle onbruikbare persona's in één keer`() {
        val fout = assertThrows(IllegalArgumentException::class.java) {
            service(
                mapOf(
                    "eerste" to instelling("Eerste B.V.", "KVK", "1234567"),
                    "tweede" to instelling("Tweede B.V.", "KVK", "7654321"),
                ),
            )
        }

        assertTrue(fout.message!!.contains("eerste") && fout.message!!.contains("tweede"), fout.message)
    }

    @Test
    fun `weigert te starten zonder magazijn`() {
        val fout = assertThrows(IllegalArgumentException::class.java) {
            PersonaService(VasteDemoConfig(mapOf("a" to instelling("A", "KVK", "12345678")), emptyMap()))
        }

        assertTrue(fout.message!!.contains("demo.magazijnen"), fout.message)
    }

    @Test
    fun `neemt de bron over uit de configuratie`() {
        val personas = service(
            mapOf(
                "keten" to instelling("A", "KVK", "12345678", listOf(TestPersonas.RVO)),
                "verzonnen" to instelling("B", "KVK", "87654321", bron = "dataset"),
            ),
        ).alle()

        assertEquals(listOf(PersonaBron.KETEN, PersonaBron.DATASET), personas.map { it.bron })
    }

    @Test
    fun `weigert een dataset-persona die ook ketenberichten zou krijgen`() {
        assertThrows(IllegalArgumentException::class.java) {
            service(mapOf("mengvorm" to instelling("Mengvorm", "KVK", "12345678", listOf(TestPersonas.RVO), "dataset")))
        }
    }

    @Test
    fun `weigert een onbekende bron`() {
        assertThrows(IllegalArgumentException::class.java) {
            service(mapOf("mock" to instelling("Mock", "KVK", "12345678", bron = "mock")))
        }
    }

    @ParameterizedTest
    @MethodSource("optIns")
    fun `alleen persona's met een opt-in krijgen gegenereerde berichten`(magazijnen: List<String>?, verwacht: List<String>) {
        val personas = service(
            mapOf(
                "pietersen" to VastePersona("J. Pietersen", "BSN", "999993653", magazijnen),
                "bakkerij" to instelling("Bakkerij De Vroege Vogel", "BSN", "999996666", listOf(TestPersonas.BELASTINGDIENST)),
                "grootbedrijf" to instelling("Grootbedrijf B.V.", "KVK", "90000001"),
            ),
        ).metMagazijnen()

        assertEquals(verwacht, personas.map { it.id })
    }

    private fun service(personas: Map<String, DemoConfig.PersonaInstelling>) = PersonaService(VasteDemoConfig(personas))

    private fun instelling(
        label: String,
        type: String,
        waarde: String,
        magazijnen: List<String>? = null,
        bron: String = "keten",
    ) = VastePersona(label, type, waarde, magazijnen, bron)

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
