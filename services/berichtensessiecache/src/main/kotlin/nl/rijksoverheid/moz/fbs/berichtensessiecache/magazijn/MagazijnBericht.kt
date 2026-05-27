package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BijlageSamenvatting
import java.time.Instant
import java.util.UUID

/**
 * Vorm van een bericht zoals het magazijn het levert: `ontvanger` is een
 * getypeerd object (`{type, waarde}`). De cache-[Bericht] gebruikt een plain
 * string als ontvanger — [toBericht] vlakt het object naar `waarde`.
 *
 * Bestaat naast cache-[Bericht] zodat Jackson-deserialisatie matcht met de
 * magazijn-spec zonder dat het cache-domein de getypeerde vorm hoeft te
 * dragen. `inhoud`, `bijlagen` en `status.map` worden uit de magazijn-respons
 * overgenomen; `tijdstipOntvangst`, `gewijzigdOp` en bijlage-`mimeType`/`_links`
 * blijven bewust buiten de cache (alleen-magazijn-gegevens).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MagazijnBericht(
    @param:JsonProperty("berichtId") val berichtId: UUID,
    @param:JsonProperty("afzender") val afzender: String,
    @param:JsonProperty("ontvanger") val ontvanger: Identificatienummer,
    @param:JsonProperty("onderwerp") val onderwerp: String,
    @param:JsonProperty("inhoud") val inhoud: String,
    @param:JsonProperty("publicatietijdstip") val publicatietijdstip: Instant,
    @param:JsonProperty("aantalBijlagen") val aantalBijlagen: Int = 0,
    @param:JsonProperty("bijlagen") val bijlagen: List<MagazijnBijlage> = emptyList(),
    @param:JsonProperty("status") val status: MagazijnBerichtStatus? = null,
) {
    fun toBericht(magazijnId: String): Bericht = Bericht(
        berichtId = berichtId,
        afzender = afzender,
        ontvanger = ontvanger.waarde,
        onderwerp = onderwerp,
        inhoud = inhoud,
        publicatietijdstip = publicatietijdstip,
        magazijnId = magazijnId,
        // Voorkeur voor expliciet `aantalBijlagen` uit het magazijn; als dat ontbreekt
        // valt het terug op de lengte van de `bijlagen`-array (consistent met de wire-vorm).
        aantalBijlagen = if (aantalBijlagen > 0 || bijlagen.isEmpty()) aantalBijlagen else bijlagen.size,
        bijlagen = bijlagen.map { BijlageSamenvatting(it.bijlageId, it.naam) },
        map = status?.map,
    )

    data class Identificatienummer(
        @param:JsonProperty("type") val type: String,
        @param:JsonProperty("waarde") val waarde: String,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MagazijnBijlage(
        @param:JsonProperty("bijlageId") val bijlageId: UUID,
        @param:JsonProperty("naam") val naam: String,
        // mimeType, _links en eventuele grootte worden bewust genegeerd: de cache
        // bewaart alleen de download-handles (id + naam); bytes blijven in het magazijn.
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MagazijnBerichtStatus(
        @param:JsonProperty("gelezen") val gelezen: Boolean? = null,
        @param:JsonProperty("map") val map: String? = null,
        // gewijzigdOp is alleen voor audit binnen het magazijn; niet relevant voor sessiecache.
    )
}
