package nl.rijksoverheid.moz.fbs.common.bijlage

import jakarta.ws.rs.core.MediaType

/**
 * Parseert het MIME-type van een bijlage voor gebruik in een response-header.
 *
 * `MediaType.valueOf` alleen is niet genoeg: `application/pdf;name="a<CR><LF>b"`
 * parseert zonder klagen en houdt de regeleindes in de parameter. De HTTP-laag
 * weigert zo'n headerwaarde bij het schrijven, waardoor élke download van die
 * bijlage klapt in plaats van netjes te falen. Control-tekens maken de waarde
 * daarom onbruikbaar, net als een vorm die niet te parsen is.
 */
object BijlageMediaType {

    /** Het geparste type, of `null` als het onbruikbaar is als headerwaarde. */
    fun parse(mimeType: String): MediaType? {
        val geparsed = runCatching { MediaType.valueOf(mimeType) }.getOrNull() ?: return null

        return geparsed.takeIf { it.toString().none { teken -> teken.category == CharCategory.CONTROL } }
    }
}
