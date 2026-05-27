package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import java.time.Instant
import java.util.UUID

/**
 * Vorm van een bericht zoals het magazijn het levert: `ontvanger` is een
 * getypeerd object (`{type, waarde}`). De cache-[Bericht] gebruikt een plain
 * string als ontvanger — [toBericht] vlakt het object naar `waarde`.
 *
 * Bestaat naast cache-[Bericht] zodat Jackson-deserialisatie matcht met de
 * magazijn-spec zonder dat het cache-domein de getypeerde vorm hoeft te
 * dragen. Onbekende velden uit de magazijn-respons (bv. `inhoud`,
 * `tijdstipOntvangst`, `bijlagen`, `_links`) worden genegeerd zodat deze
 * lijst-DTO niet meegroeit met elk magazijn-detailveld.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MagazijnBericht @JsonCreator constructor(
    @param:JsonProperty("berichtId") val berichtId: UUID,
    @param:JsonProperty("afzender") val afzender: String,
    @param:JsonProperty("ontvanger") val ontvanger: Identificatienummer,
    @param:JsonProperty("onderwerp") val onderwerp: String,
    @param:JsonProperty("publicatietijdstip") val publicatietijdstip: Instant,
    @param:JsonProperty("aantalBijlagen") val aantalBijlagen: Int = 0,
) {
    fun toBericht(magazijnId: String): Bericht = Bericht(
        berichtId = berichtId,
        afzender = afzender,
        ontvanger = ontvanger.waarde,
        onderwerp = onderwerp,
        publicatietijdstip = publicatietijdstip,
        magazijnId = magazijnId,
        aantalBijlagen = aantalBijlagen,
    )

    data class Identificatienummer @JsonCreator constructor(
        @param:JsonProperty("type") val type: String,
        @param:JsonProperty("waarde") val waarde: String,
    )
}
