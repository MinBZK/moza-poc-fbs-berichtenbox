package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import jakarta.ws.rs.core.MediaType

/**
 * Parseert het MIME-type van een bijlage voor gebruik in een response-header; `null` als het
 * onbruikbaar is.
 *
 * `MediaType.valueOf` alleen is niet genoeg: `application/pdf;name="a<CR><LF>b"` parseert zonder
 * klagen en houdt de regeleindes in de parameter. De HTTP-laag weigert zo'n headerwaarde bij het
 * schrijven, waardoor élke download van die bijlage klapt in plaats van netjes te falen.
 *
 * Een kopie van `fbs-common`'s `BijlageMediaType`, om dezelfde reden als de OIN-controle: de
 * simulator neemt bewust geen `fbs-common`. Houd beide gelijk.
 */
internal fun bijlageMediaType(mimeType: String): MediaType? {
    val geparsed = runCatching { MediaType.valueOf(mimeType) }.getOrNull() ?: return null

    return geparsed.takeIf { it.toString().none { teken -> teken.category == CharCategory.CONTROL } }
}
