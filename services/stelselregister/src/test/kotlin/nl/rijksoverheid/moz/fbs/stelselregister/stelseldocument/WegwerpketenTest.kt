package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WegwerpketenTest {

    @Test
    fun `een wachtwoord begint nooit met een streepje, want keytool leest dat als optie`() {
        val wachtwoorden = List(AANTAL) { Wegwerpketen.nieuwWachtwoord() }
        val afwijkend = wachtwoorden.filterNot { HEXADECIMAAL.matches(it) }

        assertTrue(afwijkend.isEmpty(), "geen 48 hexadecimale tekens: $afwijkend")
        assertEquals(AANTAL, wachtwoorden.toSet().size)
    }

    private companion object {
        const val AANTAL = 1_000
        val HEXADECIMAAL = Regex("[0-9a-f]{48}")
    }
}
