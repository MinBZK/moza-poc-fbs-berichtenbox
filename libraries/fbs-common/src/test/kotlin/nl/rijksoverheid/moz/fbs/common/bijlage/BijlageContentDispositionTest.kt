package nl.rijksoverheid.moz.fbs.common.bijlage

import jakarta.ws.rs.core.MediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class BijlageContentDispositionTest {

    @ParameterizedTest
    @ValueSource(strings = ["application/pdf", "image/png", "image/jpeg"])
    fun `een type dat een browser veilig toont, mag inline`(mimeType: String) {
        assertTrue(BijlageContentDisposition.magInline(MediaType.valueOf(mimeType)))
        assertEquals("inline", BijlageContentDisposition.waarde(MediaType.valueOf(mimeType), null))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "text/html",
            "image/svg+xml",
            "application/xhtml+xml",
            "text/plain",
            "application/octet-stream",
            "application/x-onbekend",
        ],
    )
    fun `elk ander type blijft een download`(mimeType: String) {
        assertFalse(BijlageContentDisposition.magInline(MediaType.valueOf(mimeType)))
        assertEquals("attachment", BijlageContentDisposition.waarde(MediaType.valueOf(mimeType), null))
    }

    @Test
    fun `parameters op het mediatype doen niet mee aan de beslissing`() {
        assertTrue(BijlageContentDisposition.magInline(MediaType.valueOf("application/pdf; charset=utf-8")))
        assertFalse(BijlageContentDisposition.magInline(MediaType.valueOf("text/html; charset=utf-8")))
    }

    @Test
    fun `hoofdletters in het mediatype veranderen de beslissing niet`() {
        assertTrue(BijlageContentDisposition.magInline(MediaType.valueOf("Application/PDF")))
        assertFalse(BijlageContentDisposition.magInline(MediaType.valueOf("TEXT/HTML")))
    }

    @Test
    fun `een wildcard-type is geen inline-type`() {
        assertFalse(BijlageContentDisposition.magInline(MediaType.WILDCARD_TYPE))
    }

    @Test
    fun `een gewone naam komt in beide filename-parameters`() {
        assertEquals(
            "inline; filename=\"aanslag-2026.pdf\"; filename*=UTF-8''aanslag-2026.pdf",
            BijlageContentDisposition.waarde(PDF, "aanslag-2026.pdf"),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `een lege of blanco naam levert alleen de dispositie op`(naam: String) {
        assertEquals("inline", BijlageContentDisposition.waarde(PDF, naam))
    }

    @Test
    fun `zonder naam levert alleen de dispositie op`() {
        assertEquals("attachment", BijlageContentDisposition.waarde(MediaType.TEXT_HTML_TYPE, null))
    }

    @Test
    fun `een niet-Latijnse naam overleeft in de filename-ster-parameter`() {
        // Grieks + Japans: de ASCII-vorm houdt er niets van over, `filename*` alles.
        val waarde = BijlageContentDisposition.waarde(PDF, "Λογαριασμός-請求書.pdf")

        assertEquals(
            "inline; filename=\"___________-___.pdf\"; " +
                "filename*=UTF-8''%CE%9B%CE%BF%CE%B3%CE%B1%CF%81%CE%B9%CE%B1%CF%83%CE%BC%CF%8C%CF%82-" +
                "%E8%AB%8B%E6%B1%82%E6%9B%B8.pdf",
            waarde,
        )
    }

    @Test
    fun `een naam met een spatie blijft leesbaar in beide parameters`() {
        assertEquals(
            "inline; filename=\"voorlopige_aanslag.pdf\"; filename*=UTF-8''voorlopige%20aanslag.pdf",
            BijlageContentDisposition.waarde(PDF, "voorlopige aanslag.pdf"),
        )
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            // Een aanhalingsteken of backslash zou de quoted-string sluiten of ontsnappen.
            "aan\"hef.pdf | aan_hef.pdf | aan%22hef.pdf",
            "aan\\hef.pdf | aan_hef.pdf | aan%5Chef.pdf",
            // Puntkomma zou een tweede parameter beginnen.
            "aan;filename=evil.html | aan_filename_evil.html | aan%3Bfilename%3Devil.html",
            // Pad-scheidingstekens mogen niet buiten de downloadmap wijzen.
            "../../etc/passwd | .._.._etc_passwd | ..%2F..%2Fetc%2Fpasswd",
            // Een percent-teken in de naam mag niet als codering gelezen worden.
            "100%korting.pdf | 100_korting.pdf | 100%25korting.pdf",
        ],
        delimiter = '|',
    )
    fun `een naam kan de header niet openbreken`(naam: String, ascii: String, gecodeerd: String) {
        assertEquals(
            "inline; filename=\"$ascii\"; filename*=UTF-8''$gecodeerd",
            BijlageContentDisposition.waarde(PDF, naam),
        )
    }

    @Test
    fun `een naam met CRLF begint geen tweede header`() {
        val waarde = BijlageContentDisposition.waarde(PDF, "nota\r\nX-Injected: ja.pdf")

        assertFalse(waarde.contains('\r'))
        assertFalse(waarde.contains('\n'))
        assertEquals(
            "inline; filename=\"nota__X-Injected__ja.pdf\"; filename*=UTF-8''nota%0D%0AX-Injected%3A%20ja.pdf",
            waarde,
        )
    }

    @Test
    fun `omringende spaties tellen niet mee`() {
        assertEquals(
            "inline; filename=\"nota.pdf\"; filename*=UTF-8''nota.pdf",
            BijlageContentDisposition.waarde(PDF, "  nota.pdf  "),
        )
    }

    @Test
    fun `een te lange naam wordt afgekapt`() {
        val naam = "a".repeat(300) + ".pdf"

        val waarde = BijlageContentDisposition.waarde(PDF, naam)

        assertEquals(
            "inline; filename=\"${"a".repeat(255)}\"; filename*=UTF-8''${"a".repeat(255)}",
            waarde,
        )
    }

    @Test
    fun `afkappen laat geen half teken achter`() {
        // Het 255e teken is de eerste helft van een surrogate-paar (emoji); die helft
        // alleen zou geen geldige UTF-8 opleveren, dus valt hij mee weg.
        val naam = "a".repeat(254) + "📄.pdf"

        val waarde = BijlageContentDisposition.waarde(PDF, naam)

        assertEquals(
            "inline; filename=\"${"a".repeat(254)}\"; filename*=UTF-8''${"a".repeat(254)}",
            waarde,
        )
    }

    private companion object {
        private val PDF: MediaType = MediaType.valueOf("application/pdf")
    }
}
