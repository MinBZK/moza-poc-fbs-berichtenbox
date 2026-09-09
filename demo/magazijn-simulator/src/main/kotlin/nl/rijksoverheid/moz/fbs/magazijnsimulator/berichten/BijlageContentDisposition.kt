package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import jakarta.ws.rs.core.MediaType

/**
 * Bouwt de `Content-Disposition` van een bijlage-download: `inline` voor typen die een browser toont
 * zonder er code uit te voeren die bij onze origin kan, `attachment` voor al het overige, met de
 * bestandsnaam erin.
 *
 * `attachment` is de fallback en niet andersom: een aangeleverde `text/html` of `image/svg+xml` zou
 * bij het openen van het download-adres onder onze origin draaien. Voor de typen hieronder bestaat
 * dat pad niet, en `X-Content-Type-Options: nosniff` verhindert dat een browser er alsnog HTML in ziet.
 *
 * Een kopie van `fbs-common`'s `BijlageContentDisposition`, om dezelfde reden als de OIN-controle en
 * de API-Version-header: de simulator neemt bewust geen `fbs-common`, want de JAX-RS-filters daarin
 * vragen om de LDV-wrapper. Wijkt dit gedrag af van het echte magazijn, dan is de simulator van
 * buitenaf te herkennen — houd beide dus gelijk.
 */
internal object BijlageContentDisposition {

    private val INLINE_VEILIGE_TYPEN = setOf("application/pdf", "image/png", "image/jpeg")

    private const val MAX_BESTANDSNAAM_LENGTE = 255

    /** Ook de Windows-vormen: `\` als scheider, `:` als drive- en stream-scheider. */
    private const val PAD_SCHEIDERS = "/\\:"

    /** De niet-alfanumerieke helft van de RFC 5987 `attr-char`-set. */
    private const val ATTR_CHAR_OVERIG = "!#${'$'}&+-.^_`|~"

    private const val HEX = "0123456789ABCDEF"

    fun waarde(mediaType: MediaType, bestandsnaam: String?): String {
        val dispositie = if (magInline(mediaType)) "inline" else "attachment"
        val naam = kapAf(gesaneerd(bestandsnaam.orEmpty()).trim()).trimEnd()

        if (naam.isEmpty()) return dispositie

        // Beide parameters, altijd: `filename` voor clients die `filename*` niet kennen
        // (RFC 6266 §4.3), `filename*` voor de naam zoals hij werkelijk is.
        return "$dispositie; filename=\"${asciiVorm(naam)}\"; filename*=UTF-8''${percentGecodeerd(naam)}"
    }

    private fun magInline(mediaType: MediaType): Boolean =
        "${mediaType.type}/${mediaType.subtype}".lowercase() in INLINE_VEILIGE_TYPEN

    /**
     * Haalt uit de naam wat hem iets anders laat zeggen dan hij is, vóór beide coderingen: de
     * browser decodeert `filename*` weer terug en geeft die parameter voorrang boven de ASCII-vorm.
     * Control-tekens dragen `\r\n`; format-tekens de bidi-overrides waarmee `salaris<U+202E>fdp.exe`
     * als `salarisexe.pdf` in beeld komt (de tag-tekens daarvan staan buiten de BMP, vandaar per
     * code point en niet per `Char`); pad-scheidingstekens maken van een naam een pad, wat RFC 6266
     * §4.3 verbiedt.
     */
    private fun gesaneerd(naam: String): String = buildString {
        naam.codePoints().forEach { codePoint ->
            val soort = Character.getType(codePoint)

            when {
                soort == Character.CONTROL.toInt() || soort == Character.FORMAT.toInt() -> Unit
                codePoint < 0x80 && codePoint.toChar() in PAD_SCHEIDERS -> append('_')
                else -> appendCodePoint(codePoint)
            }
        }
    }

    /**
     * Alles buiten de veilige ASCII-set wordt `_`. Dat vangt de aanhalingstekens die de
     * quoted-string zouden sluiten, en houdt de parameter leesbaar voor een client die
     * `filename*` niet kent.
     */
    private fun asciiVorm(naam: String): String = naam
        .map { teken -> if (teken in 'A'..'Z' || teken in 'a'..'z' || teken in '0'..'9' || teken in ".-_") teken else '_' }
        .joinToString("")

    /** De UTF-8-bytes voor `filename*` (RFC 5987), alles buiten de `attr-char`-set percent-gecodeerd. */
    private fun percentGecodeerd(naam: String): String = buildString {
        for (byte in naam.toByteArray(Charsets.UTF_8)) {
            val waarde = byte.toInt() and 0xFF
            val teken = waarde.toChar()

            if (waarde < 0x80 && (teken in 'A'..'Z' || teken in 'a'..'z' || teken in '0'..'9' || teken in ATTR_CHAR_OVERIG)) {
                append(teken)
            } else {
                append('%').append(HEX[waarde shr 4]).append(HEX[waarde and 0x0F])
            }
        }
    }

    /** Knippen op een surrogate-paar zou een half teken opleveren, vandaar de laatste stap. */
    private fun kapAf(naam: String): String {
        if (naam.length <= MAX_BESTANDSNAAM_LENGTE) return naam

        val geknipt = naam.substring(0, MAX_BESTANDSNAAM_LENGTE)

        return if (geknipt.last().isHighSurrogate()) geknipt.dropLast(1) else geknipt
    }
}
