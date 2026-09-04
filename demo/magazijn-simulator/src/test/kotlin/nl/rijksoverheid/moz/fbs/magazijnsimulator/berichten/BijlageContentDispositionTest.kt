package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import jakarta.ws.rs.core.MediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Houdt de kopie in de simulator gelijk aan die van het echte magazijn: wijkt het gedrag af, dan is
 * de simulator van buitenaf te herkennen. De gevallen hieronder spiegelen die van
 * `BijlageContentDispositionTest` in `fbs-common` — verandert er één kopie, dan valt het hier om.
 */
class BijlageContentDispositionTest {

    @ParameterizedTest
    @ValueSource(strings = ["application/pdf", "image/png", "image/jpeg", "Application/PDF"])
    fun `een type dat een browser veilig toont, mag inline`(mimeType: String) {
        assertEquals("inline", BijlageContentDisposition.waarde(MediaType.valueOf(mimeType), null))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "text/html",
            "image/svg+xml",
            "application/xhtml+xml",
            "application/pdf+xml",
            "image/pngx",
            "text/plain",
            "application/octet-stream",
            "*/*",
        ],
    )
    fun `elk ander type blijft een download`(mimeType: String) {
        assertEquals("attachment", BijlageContentDisposition.waarde(MediaType.valueOf(mimeType), null))
    }

    @Test
    fun `parameters op het mediatype doen niet mee aan de beslissing`() {
        assertEquals("inline", BijlageContentDisposition.waarde(MediaType.valueOf("application/pdf; charset=utf-8"), null))
        assertEquals("attachment", BijlageContentDisposition.waarde(MediaType.valueOf("text/html; charset=utf-8"), null))
    }

    @Test
    fun `een gewone naam komt in beide filename-parameters`() {
        assertEquals(
            "inline; filename=\"aanslag_2026.pdf\"; filename*=UTF-8''aanslag%202026.pdf",
            BijlageContentDisposition.waarde(PDF, "aanslag 2026.pdf"),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `een lege of blanco naam levert alleen de dispositie op`(naam: String) {
        assertEquals("attachment", BijlageContentDisposition.waarde(MediaType.TEXT_HTML_TYPE, naam))
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

    @ParameterizedTest
    @ValueSource(
        strings = [
            "\u202E", // RTL OVERRIDE — draait de rest van de naam om in beeld
            "\u200F", // RTL MARK
            "\u2066", // LEFT-TO-RIGHT ISOLATE
            "\u200B", // ZERO WIDTH SPACE
            "\u0000", // NUL
        ],
    )
    fun `een onzichtbaar teken kan de getoonde naam niet omkeren of verbergen`(teken: String) {
        // Zonder deze sanitatie zou `salaris<U+202E>fdp.exe` in een downloadlijst als
        // `salarisexe.pdf` verschijnen: de browser decodeert `filename*` terug en geeft die
        // parameter voorrang boven de gesaneerde ASCII-vorm.
        val waarde = BijlageContentDisposition.waarde(PDF, "salaris${teken}fdp.exe")

        assertEquals(
            "inline; filename=\"salarisfdp.exe\"; filename*=UTF-8''salarisfdp.exe",
            waarde,
        )
    }

    @Test
    fun `een naam van alleen onzichtbare tekens levert alleen de dispositie op`() {
        assertEquals("inline", BijlageContentDisposition.waarde(PDF, "\u202E\u200B\u0007"))
    }

    @Test
    fun `omringende spaties tellen niet mee`() {
        assertEquals(
            "inline; filename=\"nota.pdf\"; filename*=UTF-8''nota.pdf",
            BijlageContentDisposition.waarde(PDF, "  nota.pdf  "),
        )
    }

    @Test
    fun `een naam van precies de maximale lengte blijft heel`() {
        val naam = "a".repeat(255)

        assertEquals("inline; filename=\"$naam\"; filename*=UTF-8''$naam", BijlageContentDisposition.waarde(PDF, naam))
    }

    @Test
    fun `een te lange naam wordt afgekapt zonder half teken`() {
        val waarde = BijlageContentDisposition.waarde(PDF, "a".repeat(254) + "\uD83D\uDCC4.pdf")

        assertEquals("inline; filename=\"${"a".repeat(254)}\"; filename*=UTF-8''${"a".repeat(254)}", waarde)
    }

    @Test
    fun `een control-teken in een mediatype-parameter maakt de waarde onbruikbaar`() {
        // Zo'n waarde parseert wel, maar de HTTP-laag weigert de header pas bij het schrijven.
        assertNull(bijlageMediaType("application/pdf;name=\"a\r\nX-Injected: 1\""))
        assertEquals("application/pdf", bijlageMediaType("application/pdf")?.toString())
    }

    private companion object {
        private val PDF: MediaType = MediaType.valueOf("application/pdf")
    }
}
