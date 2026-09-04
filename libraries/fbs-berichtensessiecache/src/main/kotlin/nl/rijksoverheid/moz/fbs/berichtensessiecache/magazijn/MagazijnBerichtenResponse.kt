package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Lichte view op de magazijn-`BerichtenLijst`-response: de berichten plus de twee tellers die de
 * aggregatie gebruikt. `page`, `pageSize` en de HAL-links laten we liggen.
 *
 * De tellers zijn nullable, ook al schrijft de magazijn-spec ze als required: een magazijn is hier
 * een implementatie van derden, en een lus die volledig op andermans tellers leunt hangt of stopt
 * te vroeg zodra die onzin zijn.
 *
 * `@JsonIgnoreProperties(ignoreUnknown = true)` is noodzakelijk omdat de
 * magazijn-spec `BerichtenLijst` óók `page`, `pageSize` en `_links` als
 * required schrijft; zonder deze annotatie crasht Jackson op de eerste
 * vreemde top-level property.
 *
 * `berichten` heeft bewust géén default: een respons zonder dit veld (ontbrekend
 * of hernoemd) moet hard falen i.p.v. stil als "0 berichten, magazijn OK" door te
 * gaan — dat zou een contractbreuk maskeren. Een expliciete lege array
 * (`"berichten": []`) deserialiseert wél gewoon.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MagazijnBerichtenResponse(
    val berichten: List<MagazijnBericht>,
    val totalElements: Long? = null,
    val totalPages: Int? = null,
)
