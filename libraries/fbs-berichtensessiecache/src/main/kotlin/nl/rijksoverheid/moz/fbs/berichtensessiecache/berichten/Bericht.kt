package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.time.Instant
import java.util.UUID

/**
 * Leesstatus van een bericht. De wire-/cache-/RediSearch-representatie blijft exact de
 * bestaande lowercase strings `"gelezen"`/`"ongelezen"`; alleen het in-memory type is
 * getypeerd zodat onbekende waarden niet ongemerkt door de domeingrens lekken.
 */
enum class Leesstatus(@get:JsonValue val wire: String) {
    GELEZEN("gelezen"),
    ONGELEZEN("ongelezen");

    companion object {
        // Case-insensitive zodat upstream-callers die de wire-waarde in een andere
        // casing aanleveren (bv. een geüpperde enum-naam) op de canonieke vorm landen.
        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): Leesstatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Onbekende leesstatus: '$value'")
    }
}

data class Bericht(
    val berichtId: UUID,
    val afzender: String,
    val ontvanger: String,
    val onderwerp: String,
    val inhoud: String,
    val publicatietijdstip: Instant,
    val magazijnId: String,
    val aantalBijlagen: Int,
    val bijlagen: List<BijlageSamenvatting> = emptyList(),
    val map: String? = null,
    val status: Leesstatus? = null,
) {
    init {
        require(afzender.isNotBlank()) { "afzender mag niet leeg zijn" }
        require(ontvanger.isNotBlank()) { "ontvanger mag niet leeg zijn" }
        require(onderwerp.isNotBlank()) { "onderwerp mag niet leeg zijn" }
        // `inhoud` mag leeg zijn: niet elk magazijn levert een inhoudssamenvatting op de lijst-respons
        // (zie MagazijnBericht). Voor consumers blijft `inhoud` in de OpenAPI-spec required zodat de
        // veld-aanwezigheid stabiel is — alleen de waarde kan leeg-string zijn.
        require(magazijnId.isNotBlank()) { "magazijnId mag niet leeg zijn" }
        require(aantalBijlagen >= 0) { "aantalBijlagen mag niet negatief zijn" }
        map?.let {
            require(it.isNotBlank()) { "mapnaam mag niet leeg zijn als hij gezet is" }
            require(it.length <= MAX_MAPNAAM_LENGTE) { "mapnaam mag max $MAX_MAPNAAM_LENGTE tekens zijn" }
        }
    }

    companion object {
        // Gespiegeld op de magazijn-DB-kolom `bericht_status.map VARCHAR(128)` — een sessiecache-
        // entry met langere mapnaam zou de magazijn-write later sowieso laten falen. Constant
        // (niet configureerbaar) omdat de DB-grens hier bron van waarheid is.
        const val MAX_MAPNAAM_LENGTE = 128
    }
}

fun Bericht.toSamenvatting(): BerichtSamenvatting = BerichtSamenvatting(
    berichtId = berichtId,
    afzender = afzender,
    ontvanger = ontvanger,
    onderwerp = onderwerp,
    publicatietijdstip = publicatietijdstip,
    magazijnId = magazijnId,
    aantalBijlagen = aantalBijlagen,
    map = map,
    status = status,
)

/**
 * Lichtgewicht cache-domeintype voor lijst- en zoek-projecties uit RediSearch.
 *
 * `inhoud` en `bijlagen` ontbreken bewust: de samenvatting wordt geprojecteerd uit een
 * subset van hash-velden (zie [BerichtenCache.SAMENVATTING_VELDEN]) zodat lijst-respons
 * de — potentieel grote — `inhoud`/`bijlagen` niet over de wire haalt. De volledige
 * representatie ([Bericht]) blijft beschikbaar via `getById` (HGETALL op de hash).
 */
data class BerichtSamenvatting(
    val berichtId: UUID,
    val afzender: String,
    val ontvanger: String,
    val onderwerp: String,
    val publicatietijdstip: Instant,
    val magazijnId: String,
    val aantalBijlagen: Int,
    val map: String? = null,
    val status: Leesstatus? = null,
)

data class BijlageSamenvatting(
    val bijlageId: UUID,
    val naam: String,
) {
    init {
        require(naam.isNotBlank()) { "bijlage-naam mag niet leeg zijn" }
        // Naam-lengte-cap staat in BerichtLimieten en wordt gevalideerd door BerichtValidator.
    }
}

/** Eén pagina lijst-/zoekresultaten; element-type is altijd de lichte samenvatting. */
data class BerichtenPagina(
    val berichten: List<BerichtSamenvatting>,
    val page: Int,
    val pageSize: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    init {
        require(page >= 0) { "page mag niet negatief zijn" }
        require(pageSize > 0) { "pageSize moet positief zijn" }
        require(totalElements >= 0) { "totalElements mag niet negatief zijn" }
        require(totalPages >= 0) { "totalPages mag niet negatief zijn" }
    }
}
