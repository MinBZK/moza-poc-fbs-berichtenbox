package nl.rijksoverheid.moz.fbs.common.exception

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RequireValidTest {

    @Test
    fun `true conditie - geen exception en lazy-message wordt niet geevalueerd`() {
        val message = mockk<() -> String>()
        requireValid(true, message)
        verify(exactly = 0) { message() }
    }

    @Test
    fun `false conditie - gooit DomainValidationException met de lazy-message`() {
        val ex = assertThrows(DomainValidationException::class.java) {
            requireValid(false) { "onderwerp mag niet leeg zijn" }
        }
        assertEquals("onderwerp mag niet leeg zijn", ex.message)
    }
}
