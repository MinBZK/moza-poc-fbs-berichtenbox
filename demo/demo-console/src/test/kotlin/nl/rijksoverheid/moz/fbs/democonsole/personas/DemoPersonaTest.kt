package nl.rijksoverheid.moz.fbs.democonsole.personas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DemoPersonaTest {

    @Test
    fun `stelt de ontvanger-header samen uit type en waarde`() {
        val persona = persona(type = "KVK", waarde = "12345678")

        assertEquals("KVK:12345678", persona.ontvanger)
    }

    @ParameterizedTest
    @CsvSource(
        "BSN, 999993653",
        "RSIN, 999993653",
        "KVK, 12345678",
        "KVK, 00000001",
    )
    fun `accepteert de ontvanger-types die de demo aanbiedt`(type: String, waarde: String) {
        assertEquals("$type:$waarde", persona(type = type, waarde = waarde).ontvanger)
    }

    @Test
    fun `weigert een ontvanger-type dat niet in de header past`() {
        val fout = assertThrows(IllegalArgumentException::class.java) { persona(type = "PASPOORT", waarde = "12345678") }

        assertEquals(true, fout.message!!.contains("PASPOORT"))
    }

    @ParameterizedTest
    @CsvSource(
        "BSN, 123456789",
        "BSN, 000000000",
        "BSN, 99999365",
        "RSIN, 123456789",
        "KVK, 00000000",
        "KVK, 1234567",
        "KVK, 123456789",
        "KVK, 1234567a",
        "KVK, ''",
        "bsn, 999993653",
    )
    fun `weigert een nummer of type dat de keten niet accepteert`(type: String, waarde: String) {
        assertThrows(IllegalArgumentException::class.java) { persona(type = type, waarde = waarde) }
    }

    @Test
    fun `weigert een leeg type`() {
        assertThrows(IllegalArgumentException::class.java) { persona(type = " ") }
    }

    @Test
    fun `noemt het identificatienummer niet in de foutmelding`() {
        // Die meldingen belanden via het opstarten in de applicatielog; een nummer hoort daar niet in.
        val fout = assertThrows(IllegalArgumentException::class.java) { persona(type = "BSN", waarde = "999993652") }

        assertEquals(false, fout.message!!.contains("999993652"))
    }

    @Test
    fun `weigert hetzelfde magazijn twee keer`() {
        assertThrows(IllegalArgumentException::class.java) {
            persona(magazijnen = listOf("00000000000000100000", "00000000000000100000"))
        }
    }

    @Test
    fun `weigert een dataset-persona met magazijnen`() {
        assertThrows(IllegalArgumentException::class.java) {
            persona(magazijnen = listOf("00000000000000100000"), bron = PersonaBron.DATASET)
        }
    }

    @Test
    fun `weigert een lege id`() {
        assertThrows(IllegalArgumentException::class.java) { persona(id = " ") }
    }

    @Test
    fun `weigert een leeg label`() {
        assertThrows(IllegalArgumentException::class.java) { persona(label = " ") }
    }

    private fun persona(
        id: String = "pietersen",
        label: String = "J. Pietersen",
        type: String = "BSN",
        waarde: String = "999993653",
        magazijnen: List<String> = emptyList(),
        bron: PersonaBron = PersonaBron.KETEN,
    ) = DemoPersona(id, label, type, waarde, magazijnen, bron)
}
