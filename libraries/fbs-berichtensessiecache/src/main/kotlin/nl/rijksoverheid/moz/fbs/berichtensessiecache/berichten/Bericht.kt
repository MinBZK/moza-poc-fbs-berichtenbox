package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
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

/**
 * Een bericht zoals de sessiecache het bewaart: kopgegevens en bijlage-handles.
 *
 * `ignoreUnknown`: de list-cache bewaart dit type als JSON-blob, en tijdens een uitrol staan er
 * blobs van de vorige versie in Redis. Een veld dat wij niet meer kennen mag het teruglezen niet
 * laten stranden — zonder deze annotatie hangt dat aan een Jackson-default die nergens is
 * vastgelegd, en een andere default maakt van elke lijst-read een 500.
 *
 * De berichttekst hoort hier bewust niet bij. Die blijft in het bronmagazijn en wordt
 * opgehaald op het moment dat de ontvanger het bericht opent; vooruit kopiëren zou van
 * elk bericht de tekst in de centrale opslag zetten, ook van berichten die niemand leest
 * (data-minimalisatie, AVG art. 5(1)(c)).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Bericht(
    val berichtId: UUID,
    val afzender: String,
    /**
     * Weergavenaam van de afzendende organisatie, bij het schrijven overgenomen uit het
     * magazijnregister op [magazijnId]. De cache bewaart hem en zoekt zelf niets op, zodat een
     * bericht zijn naam houdt ook als de organisatie intussen uit het register verdwijnt — de
     * berichtenlijst mag nooit een nummer tonen waar een naam hoort. Een consumer mag deze naam
     * wél tegen een actueel register houden en de verse naam prefereren.
     */
    val afzenderNaam: String,
    @param:JsonDeserialize(using = IdentificatienummerCanonicalDeserializer::class)
    @get:JsonSerialize(using = IdentificatienummerCanonicalSerializer::class)
    val ontvanger: Identificatienummer,
    val onderwerp: String,
    val publicatietijdstip: Instant,
    val magazijnId: String,
    val aantalBijlagen: Int,
    val bijlagen: List<BijlageSamenvatting> = emptyList(),
    val map: String? = null,
    val status: Leesstatus? = null,
) {
    init {
        require(afzender.isNotBlank()) { "afzender mag niet leeg zijn" }
        require(afzenderNaam.isNotBlank()) { "afzenderNaam mag niet leeg zijn" }
        require(onderwerp.isNotBlank()) { "onderwerp mag niet leeg zijn" }
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
    afzenderNaam = afzenderNaam,
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
 * `bijlagen` ontbreekt bewust: de samenvatting wordt geprojecteerd uit een subset van
 * hash-velden (zie [BerichtenCache.SAMENVATTING_VELDEN]), zodat lijst en detail elk één
 * vaste vorm houden. De volledige representatie ([Bericht]) blijft beschikbaar via
 * `getById` (HGETALL op de hash).
 */
data class BerichtSamenvatting(
    val berichtId: UUID,
    val afzender: String,
    /** Zie [Bericht.afzenderNaam]. */
    val afzenderNaam: String,
    val ontvanger: Identificatienummer,
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
