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
    )
    fun `accepteert de ontvanger-types die de uitvraag kent`(type: String, waarde: String) {
        assertEquals("$type:$waarde", persona(type = type, waarde = waarde).ontvanger)
    }

    @Test
    fun `weigert een ontvanger-type dat niet in de header past`() {
        val fout = assertThrows(IllegalArgumentException::class.java) { persona(type = "PASPOORT", waarde = "12345678") }

        assertEquals(true, fout.message!!.contains("PASPOORT"))
    }

    @Test
    fun `weigert een nummer dat de elfproef niet doorstaat`() {
        assertThrows(IllegalArgumentException::class.java) { persona(type = "BSN", waarde = "123456789") }
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
