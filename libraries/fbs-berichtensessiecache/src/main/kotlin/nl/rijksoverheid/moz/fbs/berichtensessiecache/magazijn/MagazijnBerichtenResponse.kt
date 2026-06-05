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
 *
 * `berichten` heeft bewust géén default: een respons zonder dit veld (ontbrekend
 * of hernoemd) moet hard falen i.p.v. stil als "0 berichten, magazijn OK" door te
 * gaan — dat zou een contractbreuk maskeren. Een expliciete lege array
 * (`"berichten": []`) deserialiseert wél gewoon.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MagazijnBerichtenResponse(
    val berichten: List<MagazijnBericht>,
)
