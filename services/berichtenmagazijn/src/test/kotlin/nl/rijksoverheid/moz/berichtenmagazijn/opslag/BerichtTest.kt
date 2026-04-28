package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BerichtTest {

    private fun bericht(
        afzender: Oin = Oin("00000001003214345000"),
        ontvanger: Identificatienummer = Bsn("999993653"),
        onderwerp: String = "Onderwerp",
        inhoud: String = "Inhoud",
    ) = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = afzender,
        ontvanger = ontvanger,
        onderwerp = onderwerp,
        inhoud = inhoud,
        tijdstipOntvangst = Instant.now(),
    )

    @Test
    fun `geldige velden construeren zonder fout`() {
        val b = bericht()
        assertEquals("00000001003214345000", b.afzender.waarde)
    }

    @Test
    fun `blank onderwerp faalt`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { bericht(onderwerp = "\t") }
        assertEquals("onderwerp mag niet leeg zijn", ex.message)
    }

    @Test
    fun `blank inhoud faalt`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { bericht(inhoud = "   ") }
        assertEquals("inhoud mag niet leeg zijn", ex.message)
    }

    @Test
    fun `te lange onderwerp faalt`() {
        val lang = "x".repeat(Bericht.MAX_ONDERWERP_LENGTE + 1)
        val ex = assertThrows(IllegalArgumentException::class.java) { bericht(onderwerp = lang) }
        assertEquals("onderwerp mag max ${Bericht.MAX_ONDERWERP_LENGTE} characters zijn", ex.message)
    }

    @Test
    fun `te lange inhoud faalt`() {
        // ASCII = 1 UTF-8 byte/char, dus byte-grens overschrijden vereist één extra byte.
        val lang = "x".repeat(Bericht.MAX_INHOUD_BYTES + 1)
        val ex = assertThrows(IllegalArgumentException::class.java) { bericht(inhoud = lang) }
        val miB = Bericht.MAX_INHOUD_BYTES / 1024 / 1024
        assertEquals(
            "inhoud mag max $miB MiB UTF-8 zijn (kreeg ${Bericht.MAX_INHOUD_BYTES + 1} bytes)",
            ex.message,
        )
    }
}
