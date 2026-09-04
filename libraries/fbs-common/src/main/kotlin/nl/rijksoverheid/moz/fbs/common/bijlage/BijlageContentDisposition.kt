package nl.rijksoverheid.moz.fbs.common.bijlage

import jakarta.ws.rs.core.MediaType

/**
 * Bouwt de `Content-Disposition`-header voor een bijlage-download: `inline` voor
 * typen die een browser toont zonder er code uit te voeren, `attachment` voor al
 * het overige, met de bestandsnaam erin.
 *
 * `attachment` is de fallback en niet andersom, omdat een aangeleverde
 * `text/html` of `image/svg+xml` bij top-level navigatie naar het download-adres
 * onder onze origin zou draaien (stored XSS). CSP `frame-ancestors 'none'` dekt
 * die navigatie niet — die geldt alleen voor iframes. Voor de typen op
 * [INLINE_VEILIGE_TYPEN] bestaat dat pad niet: een browser voert er geen code uit
 * die bij de DOM of de cookies van onze origin kan — de PDF-viewer draait het
 * JavaScript in een PDF wél, maar afgeschermd — en `X-Content-Type-Options:
 * nosniff` (globaal gezet op beide diensten) verhindert dat hij er alsnog HTML
 * in ziet.
 *
 * De dispositie telt voor de aanroeper die de bytes aan een mens toont. Een
 * browser bereikt deze endpoints niet rechtstreeks — ze eisen de header
 * `X-Ontvanger`, die bij top-level navigatie niet te zetten is — dus het is een
 * berichtenbox die de keten server-side aanroept die deze keuze doorgeeft aan
 * zijn eigen gebruiker. Wordt `X-Ontvanger` ooit optioneel, dan raakt dat deze
 * afweging: dan is het download-adres wél rechtstreeks te openen.
 *
 * Beide diensten gebruiken deze functie, zodat een afnemer die rechtstreeks bij
 * een magazijn ophaalt hetzelfde antwoord krijgt als via de uitvraag. De uitvraag
 * beslist daarbij op het type dat het bronmagazijn meestuurt: `inline` reikt niet
 * verder dan het vertrouwen in de magazijnen in het register.
 */
object BijlageContentDisposition {

    /**
     * Typen waarvan de weergave in een browser geen code uitvoert die bij onze
     * origin kan. Uitbreiden mag alleen met een type waarvoor dat óók geldt:
     * `text/html`, `image/svg+xml` en `application/xhtml+xml` horen er per
     * definitie niet bij, en een type waarvan je het niet zeker weet evenmin.
     */
    private val INLINE_VEILIGE_TYPEN = setOf("application/pdf", "image/png", "image/jpeg")

    /**
     * Headerwaarde voor [mediaType], met [bestandsnaam] als die er is. De naam
     * wordt hier gesaneerd en gecodeerd — niet bij de aanroeper — zodat geen
     * enkele route een onbewerkte naam in een header kan zetten.
     */
    fun waarde(mediaType: MediaType, bestandsnaam: String?): String {
        val dispositie = if (magInline(mediaType)) "inline" else "attachment"
        val naam = kapAf(gesaneerd(bestandsnaam.orEmpty()).trim()).trimEnd()

        if (naam.isEmpty()) return dispositie

        // Beide parameters, altijd: `filename` voor clients die `filename*` niet
        // kennen (RFC 6266 §4.3), `filename*` voor de naam zoals hij werkelijk is.
        return "$dispositie; filename=\"${asciiVorm(naam)}\"; filename*=UTF-8''${percentGecodeerd(naam)}"
    }

    /** Of [mediaType] getoond mag worden; parameters als `charset` doen niet mee. */
    private fun magInline(mediaType: MediaType): Boolean =
        "${mediaType.type}/${mediaType.subtype}".lowercase() in INLINE_VEILIGE_TYPEN

    /**
     * Haalt uit de naam wat hem iets anders laat zeggen dan hij is. Dit gebeurt vóór
     * beide coderingen, want de browser decodeert `filename*` weer terug en geeft die
     * parameter voorrang boven de ASCII-vorm — saneren in [asciiVorm] alleen zou dus
     * niets afdwingen.
     *
     * - Control-tekens dragen `\r\n`, waarmee een naam een tweede header zou beginnen.
     * - Format-tekens dragen de bidi-overrides: `salaris<U+202E>fdp.exe` toont in een
     *   downloadlijst als `salarisexe.pdf`. De tag-tekens (U+E0000-blok) horen er ook
     *   bij; die staan buiten de BMP, vandaar dat we per code point lopen en niet per
     *   `Char` — als `Char` is elke helft een surrogate en nooit een format-teken.
     * - Pad-scheidingstekens maken van een naam een pad. RFC 6266 §4.3 verbiedt dat, en
     *   een afnemer die de naam server-side hergebruikt zou ermee buiten zijn map wijzen.
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
     * De naam voor de `filename`-parameter: alles buiten de veilige ASCII-set wordt
     * `_`. Dat vangt in één regel de aanhalingstekens die de quoted-string zouden
     * sluiten, en houdt de parameter leesbaar voor een client die `filename*` niet kent.
     */
    private fun asciiVorm(naam: String): String = naam
        .map { teken -> if (teken in 'A'..'Z' || teken in 'a'..'z' || teken in '0'..'9' || teken in ".-_") teken else '_' }
        .joinToString("")

    /**
     * De naam voor de `filename*`-parameter (RFC 5987): de UTF-8-bytes, waarvan
     * alles buiten de `attr-char`-set percent-gecodeerd is. Zo overleeft een
     * niet-Latijnse naam de header zonder hem te kunnen breken.
     */
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

    /**
     * Houdt de header hanteerbaar: de magazijn-invariant begrenst een bijlage-naam
     * al op 255 tekens, maar een percent-gecodeerde naam is een veelvoud daarvan en
     * de aanroeper is niet altijd het magazijn. Knippen op een surrogate-paar zou
     * een half teken opleveren, vandaar de laatste stap.
     */
    private fun kapAf(naam: String): String {
        if (naam.length <= MAX_BESTANDSNAAM_LENGTE) return naam

        val geknipt = naam.substring(0, MAX_BESTANDSNAAM_LENGTE)

        return if (geknipt.last().isHighSurrogate()) geknipt.dropLast(1) else geknipt
    }

    private const val MAX_BESTANDSNAAM_LENGTE = 255

    /** Ook de Windows-vormen: `\` als scheider, `:` als drive- en stream-scheider. */
    private const val PAD_SCHEIDERS = "/\\:"

    /** De niet-alfanumerieke helft van de RFC 5987 `attr-char`-set. */
    private const val ATTR_CHAR_OVERIG = "!#\$&+-.^_`|~"

    private const val HEX = "0123456789ABCDEF"
}
