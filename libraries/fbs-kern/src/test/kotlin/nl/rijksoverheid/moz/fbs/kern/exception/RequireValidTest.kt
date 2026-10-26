package nl.rijksoverheid.moz.fbs.kern.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RequireValidTest {

    @Test
    fun `true conditie - geen exception en lazy-message wordt niet geevalueerd`() {
        var aanroepen = 0

        requireValid(true) {
            aanroepen++
            "mag niet opgebouwd worden"
        }

        assertEquals(0, aanroepen)
    }

    @Test
    fun `false conditie - gooit DomainValidationException met de lazy-message`() {
        val ex = assertThrows(DomainValidationException::class.java) {
            requireValid(false) { "onderwerp mag niet leeg zijn" }
        }
        assertEquals("onderwerp mag niet leeg zijn", ex.message)
    }
}
