package nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag

import java.time.Instant
import java.util.UUID

/**
 * Een opgeslagen bericht, losgekoppeld van de JPA-entity zodat de resources niet met
 * persistentie-objecten werken.
 *
 * De invarianten hieronder zijn dezelfde die het echte magazijn in zijn servicelaag afdwingt. Ze
 * staan er niet voor de sier: zonder deze checks accepteert de simulator aanleveringen waar een
 * echt magazijn 400 op geeft, en dan demonstreert de demo iets dat in werkelijkheid niet lukt.
 */
data class Bericht(
    val berichtId: UUID,
    val afzender: Identificatie,
    val ontvanger: Identificatie,
    val onderwerp: String,
    val inhoud: String,
    val tijdstipOntvangst: Instant,
    val publicatietijdstip: Instant,
    val bijlagen: List<BijlageMetadata> = emptyList(),
    /** `null` zolang de ontvanger het bericht niet heeft aangeraakt — zie [BerichtStatus]. */
    val status: BerichtStatus? = null,
) {
    init {
        vereis(afzender.type == IdentificatieType.OIN) { "Afzender moet een OIN zijn" }
        vereis(onderwerp.isNotBlank()) { "Onderwerp mag niet leeg zijn" }
        vereis(onderwerp.length <= MAX_ONDERWERP_LENGTE) {
            "Onderwerp mag maximaal $MAX_ONDERWERP_LENGTE tekens zijn"
        }
        vereis(inhoud.isNotBlank()) { "Inhoud mag niet leeg zijn" }

        // De grens is in UTF-8 bytes, niet in tekens: een emoji van vier bytes hoort vier keer zo
        // zwaar te tellen. De goedkope voorcheck voorkomt dat elke aanlevering een megabyte
        // encodeert; hooguit vier bytes per teken, dus onder die drempel kán het niet te groot zijn.
        if (inhoud.length > MAX_INHOUD_BYTES / MAX_BYTES_PER_TEKEN) {
            val bytes = inhoud.toByteArray(Charsets.UTF_8).size

            vereis(bytes <= MAX_INHOUD_BYTES) {
                "Inhoud mag maximaal ${MAX_INHOUD_BYTES / 1024 / 1024} MiB UTF-8 zijn (kreeg $bytes bytes)"
            }
        }

        // Volledige identiteit vergelijken: twee types met dezelfde cijferreeks zijn verschillende
        // identificatienummers.
        vereis(afzender != ontvanger) { "Afzender en ontvanger mogen niet hetzelfde nummer hebben" }
    }

    companion object {
        const val MAX_ONDERWERP_LENGTE = 255

        /** 1 MiB, gelijk aan `Bericht.MAX_INHOUD_BYTES` van het echte magazijn. */
        const val MAX_INHOUD_BYTES = 1_048_576

        private const val MAX_BYTES_PER_TEKEN = 4
    }
}

/**
 * Leesstatus van een bericht voor zijn ontvanger.
 *
 * Afwezigheid (`Bericht.status == null`) betekent "nog niet aangeraakt", en dat is iets anders dan
 * `gelezen = false`: bij het verplaatsen naar een map heeft de ontvanger het bericht wél
 * aangeraakt. De spec maakt precies dat onderscheid door `status` weg te laten zolang er niets is
 * gezet.
 */
data class BerichtStatus(
    val gelezen: Boolean,
    val map: String?,
    val gewijzigdOp: Instant,
) {
    init {
        if (map != null) {
            vereis(map.isNotBlank()) { "Mapnaam mag niet leeg zijn" }
            vereis(map.length <= MAX_MAPNAAM_LENGTE) {
                "Mapnaam mag maximaal $MAX_MAPNAAM_LENGTE tekens zijn"
            }
        }
    }

    companion object {
        /** Gelijk aan `BerichtStatusPatch.map.maxLength` in de spec en aan de kolombreedte in V1. */
        const val MAX_MAPNAAM_LENGTE = 128
    }
}

/**
 * Wijziging op de status van een bericht, met de merge-patch-semantiek van de spec: een veld dat
 * `null` is blijft ongewijzigd. Dat is geen detail — het verschil tussen "niet meegestuurd" en "op
 * niets zetten" is precies wat een `PATCH` op een map betekent.
 */
data class BerichtStatusWijziging(val gelezen: Boolean?, val map: String?) {
    val isLeeg: Boolean get() = gelezen == null && map == null
}

/** Metadata van een bijlage; de bytes worden apart opgehaald. */
data class BijlageMetadata(
    val bijlageId: UUID,
    val naam: String,
    val mimeType: String,
)

/** Een bijlage inclusief bytes, zoals de download-endpoint hem teruggeeft. */
data class Bijlage(
    val bijlageId: UUID,
    val naam: String,
    val mimeType: String,
    val inhoud: ByteArray,
) {
    init {
        vereis(naam.isNotBlank()) { "Bijlagenaam mag niet leeg zijn" }
        vereis(naam.length <= MAX_NAAM_LENGTE) { "Bijlagenaam mag maximaal $MAX_NAAM_LENGTE tekens zijn" }
        vereis(mimeType.isNotBlank()) { "MIME-type mag niet leeg zijn" }
        vereis(mimeType.length <= MAX_MIME_LENGTE) { "MIME-type mag maximaal $MAX_MIME_LENGTE tekens zijn" }
        vereis(inhoud.isNotEmpty()) { "Bijlage mag niet leeg zijn" }
        vereis(inhoud.size <= MAX_INHOUD_BYTES) {
            "Bijlage mag maximaal ${MAX_INHOUD_BYTES / 1024 / 1024} MiB zijn (kreeg ${inhoud.size} bytes)"
        }
    }

    companion object {
        const val MAX_NAAM_LENGTE = 255
        const val MAX_MIME_LENGTE = 127

        /**
         * 25 MiB, gelijk aan het echte magazijn. Géén spec-grens — die kent alleen `minLength: 1` —
         * maar wél waarneembaar gedrag: een bijlage die het echte magazijn met 400 weigert, hoort de
         * simulator ook te weigeren.
         *
         * Wat de simulator bewust NIET overneemt is de eis dat het MIME-type `application/pdf` moet
         * zijn. Die staat evenmin in de spec, maar hij is een beleidskeuze van dát magazijn en hij
         * zou de demo armer maken: een berichtenbox met alleen PDF's laat het bijlage-pad maar half
         * zien. De spec laat elk MIME-type toe en de simulator volgt de spec.
         */
        const val MAX_INHOUD_BYTES = 26_214_400
    }

    // Een ByteArray vergelijkt op referentie; data class-equals zou dus twee gelijke bijlagen
    // ongelijk noemen. Expliciet uitgeschreven zodat tests op inhoud kunnen vergelijken.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Bijlage) return false

        return bijlageId == other.bijlageId &&
            naam == other.naam &&
            mimeType == other.mimeType &&
            inhoud.contentEquals(other.inhoud)
    }

    fun metadata(): BijlageMetadata = BijlageMetadata(bijlageId = bijlageId, naam = naam, mimeType = mimeType)

    override fun hashCode(): Int {
        var resultaat = bijlageId.hashCode()

        resultaat = 31 * resultaat + naam.hashCode()
        resultaat = 31 * resultaat + mimeType.hashCode()
        resultaat = 31 * resultaat + inhoud.contentHashCode()

        return resultaat
    }
}

/** Eén pagina uit de berichtenlijst, met wat de HAL-links nodig hebben. */
data class BerichtenPagina(
    val berichten: List<Bericht>,
    val page: Int,
    val pageSize: Int,
    val totalElements: Long,
) {
    init {
        // `require` en geen `vereis`: dit zijn geen invoer-invarianten maar interne consistentie. De
        // spec begrenst `page` en `pageSize` al op de query-parameter, dus een schending hier is een
        // programmeerfout en hoort een 500 te worden, geen 400 die de aanroeper op het verkeerde
        // spoor zet.
        require(page >= 0) { "page mag niet negatief zijn (kreeg $page)" }
        require(pageSize > 0) { "pageSize moet groter dan 0 zijn (kreeg $pageSize)" }
        require(totalElements >= 0) { "totalElements mag niet negatief zijn (kreeg $totalElements)" }
    }

    val totalPages: Int = ((totalElements + pageSize - 1) / pageSize).toInt()
}
