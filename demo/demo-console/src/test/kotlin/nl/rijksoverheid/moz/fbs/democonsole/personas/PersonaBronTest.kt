package nl.rijksoverheid.moz.fbs.democonsole.personas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PersonaBronTest {

    @ParameterizedTest
    @CsvSource(
        "keten, KETEN",
        "KETEN, KETEN",
        "dataset, DATASET",
    )
    fun `leest de bron ongeacht hoofdletters`(waarde: String, verwacht: PersonaBron) {
        assertEquals(verwacht, PersonaBron.van(waarde))
    }

    @Test
    fun `noemt bij een onbekende bron de toegestane waarden, niet de aangeboden waarde`() {
        val melding = assertThrows(IllegalArgumentException::class.java) { PersonaBron.van("mock") }.message!!

        assertTrue(melding.contains("keten"), melding)
        assertTrue(melding.contains("dataset"), melding)
        assertFalse(melding.contains("mock"), "de aangeboden waarde hoort niet in de melding")
    }
}
