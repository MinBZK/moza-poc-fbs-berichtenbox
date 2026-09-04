package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import jakarta.ws.rs.core.MediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Houdt de kopie in de simulator gelijk aan die van het echte magazijn: wijkt het gedrag af, dan is
 * de simulator van buitenaf te herkennen.
 */
class BijlageContentDispositionTest {

    @ParameterizedTest
    @ValueSource(strings = ["application/pdf", "image/png", "image/jpeg"])
    fun `een type dat een browser veilig toont, mag inline`(mimeType: String) {
        assertEquals("inline", BijlageContentDisposition.waarde(MediaType.valueOf(mimeType), null))
    }

    @ParameterizedTest
    @ValueSource(strings = ["text/html", "image/svg+xml", "text/plain", "application/octet-stream"])
    fun `elk ander type blijft een download`(mimeType: String) {
        assertEquals("attachment", BijlageContentDisposition.waarde(MediaType.valueOf(mimeType), null))
    }

    @Test
    fun `parameters op het mediatype doen niet mee aan de beslissing`() {
        assertEquals("inline", BijlageContentDisposition.waarde(MediaType.valueOf("application/pdf; charset=utf-8"), null))
    }

    @Test
    fun `een gewone naam komt in beide filename-parameters`() {
        assertEquals(
            "inline; filename=\"aanslag_2026.pdf\"; filename*=UTF-8''aanslag%202026.pdf",
            BijlageContentDisposition.waarde(MediaType.valueOf("application/pdf"), "aanslag 2026.pdf"),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `een lege of blanco naam levert alleen de dispositie op`(naam: String) {
        assertEquals("attachment", BijlageContentDisposition.waarde(MediaType.TEXT_HTML_TYPE, naam))
    }

    @Test
    fun `een naam kan de header niet openbreken`() {
        val waarde = BijlageContentDisposition.waarde(
            MediaType.valueOf("application/pdf"),
            "Λογαριασμός\"; drop\r\n.pdf",
        )

        assertEquals(
            "inline; filename=\"______________drop__.pdf\"; " +
                "filename*=UTF-8''%CE%9B%CE%BF%CE%B3%CE%B1%CF%81%CE%B9%CE%B1%CF%83%CE%BC%CF%8C%CF%82%22%3B%20drop%0D%0A.pdf",
            waarde,
        )
    }

    @Test
    fun `een te lange naam wordt afgekapt zonder half teken`() {
        val waarde = BijlageContentDisposition.waarde(MediaType.valueOf("application/pdf"), "a".repeat(254) + "📄.pdf")

        assertEquals(
            "inline; filename=\"${"a".repeat(254)}\"; filename*=UTF-8''${"a".repeat(254)}",
            waarde,
        )
    }
}
