package nl.rijksoverheid.moz.fbs.democonsole.personas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `noemt de toegestane waarden bij een onbekende bron`() {
        val fout = assertThrows(IllegalArgumentException::class.java) { PersonaBron.van("mock") }

        assertEquals(true, fout.message!!.contains("keten"))
        assertEquals(true, fout.message!!.contains("dataset"))
    }
}
