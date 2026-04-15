package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@QuarkusTest
class BerichtTest {

    private fun bericht(
        afzender: String = "00000001003214345000",
        ontvanger: String = "999993653",
        onderwerp: String = "Onderwerp",
        inhoud: String = "Inhoud",
    ) = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = afzender,
        ontvanger = ontvanger,
        onderwerp = onderwerp,
        inhoud = inhoud,
        tijdstip = Instant.now(),
    )

    @Test
    fun `geldige velden construeren zonder fout`() {
        val b = bericht()
        assertEquals("00000001003214345000", b.afzender)
    }

    @Test
    fun `blanke afzender faalt met duidelijke boodschap`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { bericht(afzender = "  ") }
        assertEquals("afzender mag niet leeg zijn", ex.message)
    }

    @Test
    fun `blanke ontvanger faalt`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { bericht(ontvanger = "") }
        assertEquals("ontvanger mag niet leeg zijn", ex.message)
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
}
