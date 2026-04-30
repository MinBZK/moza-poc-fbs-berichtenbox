package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class BerichtTest {

    private val geldigBericht = Bericht(
        berichtId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        afzender = "00000001234567890000",
        ontvanger = "999993653",
        onderwerp = "Test bericht",
        tijdstip = Instant.parse("2026-03-10T10:00:00Z"),
        magazijnId = "magazijn-a",
    )

    @Test
    fun `lege afzender wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            geldigBericht.copy(afzender = "")
        }
        assertEquals("afzender mag niet leeg zijn", ex.message)
    }

    @Test
    fun `blanco afzender wordt geweigerd`() {
        assertThrows<IllegalArgumentException> {
            geldigBericht.copy(afzender = "   ")
        }
    }

    @Test
    fun `lege ontvanger wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            geldigBericht.copy(ontvanger = "")
        }
        assertEquals("ontvanger mag niet leeg zijn", ex.message)
    }

    @Test
    fun `lege onderwerp wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            geldigBericht.copy(onderwerp = "")
        }
        assertEquals("onderwerp mag niet leeg zijn", ex.message)
    }

    @Test
    fun `lege magazijnId wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> {
            geldigBericht.copy(magazijnId = "")
        }
        assertEquals("magazijnId mag niet leeg zijn", ex.message)
    }
}
