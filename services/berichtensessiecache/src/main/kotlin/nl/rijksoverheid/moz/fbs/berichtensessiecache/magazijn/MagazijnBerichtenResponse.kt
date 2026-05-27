package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Lichte view op de magazijn-`BerichtenLijst`-response. Wij hebben hier
 * alleen `berichten` nodig; paginering, totalen en HAL-links zijn voor
 * de sessiecache niet relevant — die rolt magazijnen niet pagineerbaar
 * door, het haalt per ophalen-call één pagina op en aggregeert.
 *
 * `@JsonIgnoreProperties(ignoreUnknown = true)` is noodzakelijk omdat de
 * magazijn-spec `BerichtenLijst` óók `page`, `pageSize`, `totalElements`,
 * `totalPages` en `_links` als required schrijft; zonder deze annotatie
 * crasht Jackson op de eerste vreemde top-level property.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MagazijnBerichtenResponse(
    val berichten: List<MagazijnBericht> = emptyList(),
)
