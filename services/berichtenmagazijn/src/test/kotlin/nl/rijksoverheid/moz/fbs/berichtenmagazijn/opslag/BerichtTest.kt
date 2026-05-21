package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

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
        tijdstipOntvangst: Instant = Instant.now(),
        publicatiedatum: Instant = tijdstipOntvangst,
    ) = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = afzender,
        ontvanger = ontvanger,
        onderwerp = onderwerp,
        inhoud = inhoud,
        tijdstipOntvangst = tijdstipOntvangst,
        publicatiedatum = publicatiedatum,
    )

    @Test
    fun `geldige velden construeren zonder fout`() {
        val b = bericht()
        assertEquals("00000001003214345000", b.afzender.waarde)
    }

    @Test
    fun `blank onderwerp faalt`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { bericht(onderwerp = "\t") }
        assertEquals("Onderwerp mag niet leeg zijn", ex.message)
    }

    @Test
    fun `blank inhoud faalt`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { bericht(inhoud = "   ") }
        assertEquals("Inhoud mag niet leeg zijn", ex.message)
    }

    @Test
    fun `te lange onderwerp faalt`() {
        val lang = "x".repeat(Bericht.MAX_ONDERWERP_LENGTE + 1)
        val ex = assertThrows(IllegalArgumentException::class.java) { bericht(onderwerp = lang) }
        assertEquals("Onderwerp mag max ${Bericht.MAX_ONDERWERP_LENGTE} characters zijn", ex.message)
    }

    @Test
    fun `te lange inhoud faalt`() {
        // ASCII = 1 UTF-8 byte/char, dus byte-grens overschrijden vereist één extra byte.
        val lang = "x".repeat(Bericht.MAX_INHOUD_BYTES + 1)
        val ex = assertThrows(IllegalArgumentException::class.java) { bericht(inhoud = lang) }
        val miB = Bericht.MAX_INHOUD_BYTES / 1024 / 1024
        assertEquals(
            "Inhoud mag max $miB MiB UTF-8 zijn (kreeg ${Bericht.MAX_INHOUD_BYTES + 1} bytes)",
            ex.message,
        )
    }

    @Test
    fun `publicatiedatum gelijk aan tijdstipOntvangst is geldig`() {
        val nu = Instant.parse("2026-05-12T10:00:00Z")
        val b = bericht(tijdstipOntvangst = nu, publicatiedatum = nu)
        assertEquals(nu, b.publicatiedatum)
    }

    @Test
    fun `publicatiedatum in de toekomst is geldig`() {
        val nu = Instant.parse("2026-05-12T10:00:00Z")
        val toekomst = nu.plusSeconds(86_400)
        val b = bericht(tijdstipOntvangst = nu, publicatiedatum = toekomst)
        assertEquals(toekomst, b.publicatiedatum)
    }

    @Test
    fun `publicatiedatum in het verleden is toegestaan (late her-aanlevering)`() {
        // Bij een late her-aanlevering kan de oorspronkelijke publicatiedatum al verstreken
        // zijn; dat mag — de outbox publiceert dan direct.
        val nu = Instant.parse("2026-05-12T10:00:00Z")
        val b = bericht(tijdstipOntvangst = nu, publicatiedatum = nu.minusSeconds(60))
        assertEquals(nu.minusSeconds(60), b.publicatiedatum)
    }
}
