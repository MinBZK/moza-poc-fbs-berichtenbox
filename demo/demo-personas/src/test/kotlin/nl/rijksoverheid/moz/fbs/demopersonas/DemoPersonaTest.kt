package nl.rijksoverheid.moz.fbs.demopersonas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DemoPersonaTest {

    @Test
    fun `stelt de ontvanger-header samen uit type en waarde`() {
        val persona = persona(type = "KVK", waarde = "90000014")

        assertEquals("KVK:90000014", persona.ontvanger)
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
    fun `weigert een ontvanger-type dat de demo niet aanbiedt en noemt wat wel mag`() {
        val fout = assertThrows(IllegalArgumentException::class.java) { persona(type = "PASPOORT", waarde = "90000014") }

        assertTrue(fout.message!!.contains("BSN"), fout.message)
        assertFalse(fout.message!!.contains("PASPOORT"), "de aangeboden waarde hoort niet in de melding")
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

    @ParameterizedTest
    @CsvSource(
        "BSN, 999993652",
        "BSN, 12345",
        "BSN, 000000000",
        "RSIN, 999993652",
        "KVK, 1234567",
        "KVK, 00000000",
        "999993653, BSN",
    )
    fun `noemt geen identificatienummer in de foutmelding, ook niet bij verwisselde velden`(type: String, waarde: String) {
        val fout = assertThrows(IllegalArgumentException::class.java) { persona(type = type, waarde = waarde) }

        // Elke reeks van acht of meer cijfers is een identificatienummer; het type mag de melding
        // wel noemen, dat is geen persoonsgegeven.
        assertFalse(NUMMER.containsMatchIn(fout.message!!), fout.message)
    }

    @Test
    fun `weigert een nummer als persona-id, want de id komt wel in meldingen`() {
        assertThrows(IllegalArgumentException::class.java) { persona(id = "999993653") }
    }

    @Test
    fun `accepteert twee verschillende magazijnen`() {
        assertEquals(2, persona(magazijnen = listOf("00000000000000100000", "00000001823288444000")).magazijnen.size)
    }

    @Test
    fun `weigert een leeg magazijn-OIN`() {
        // Tot deze module bestond ving de magazijn-kruiscontrole in de service dit ook op. Die
        // controle staat nu in de demo-console, dus zonder deze test is de invariant hier alleen
        // nog per ongeluk gedekt vanuit een test die er niet over gaat.
        val fout = assertThrows(IllegalArgumentException::class.java) {
            persona(magazijnen = listOf("00000000000000100000", ""))
        }

        assertTrue(fout.message!!.contains("leeg magazijn"), fout.message)
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

    private val NUMMER = Regex("[0-9]{8,}")

    private fun persona(
        id: String = "pietersen",
        label: String = "J. Pietersen",
        type: String = "BSN",
        waarde: String = "999993653",
        magazijnen: List<String> = emptyList(),
        bron: PersonaBron = PersonaBron.KETEN,
    ) = DemoPersona(id, label, type, waarde, magazijnen, bron)
}
