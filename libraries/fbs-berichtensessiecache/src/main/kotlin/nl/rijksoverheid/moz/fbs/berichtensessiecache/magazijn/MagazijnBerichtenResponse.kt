package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Lichte view op de magazijn-`BerichtenLijst`-response: de berichten zelf plus de twee tellers
 * die de aggregatie gebruikt. `page`, `pageSize` en de HAL-links laten we liggen — de
 * aanroeper weet welke pagina hij vroeg en bouwt de volgende zelf.
 *
 * `totalElements` en `totalPages` zijn nullable, ook al schrijft de magazijn-spec ze als
 * required. Een magazijn is in dit stelsel een implementatie van derden; een pagineerlus die
 * volledig op andermans tellers vertrouwt, hangt of stopt te vroeg zodra die tellers onzin zijn.
 * De lus stopt daarom primair op een niet-volle pagina en gebruikt deze twee alleen als extra
 * stopvoorwaarde en als getal achter het afkap-signaal naar de gebruiker.
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
