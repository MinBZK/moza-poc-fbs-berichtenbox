package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.exception.requireValid
import java.util.UUID

/**
 * Volledig domeinmodel van een bijlage, inclusief inhoud (bytes).
 * Wordt gebruikt door de Aanlever-flow (opslaan) en door `getBijlage` (uitlevering).
 *
 * `content` wordt **niet** defensief gekopieerd; aanroepers MOGEN deze bytes
 * niet muteren. Een kopie bij elke leesactie zou voor bijlagen tot 25 MiB de
 * heap-druk verdubbelen. De alternatieve aanpak (private backing field +
 * `bytes(): ByteArray`-getter) is bewust niet gekozen om dezelfde reden.
 */
data class Bijlage(
    val bijlageId: UUID,
    val berichtId: UUID,
    val naam: String,
    val mimeType: String,
    val content: ByteArray,
) {
    init {
        requireValid(naam.isNotBlank()) { "Bijlage-naam mag niet leeg zijn" }
        requireValid(naam.length <= MAX_NAAM_LENGTE) {
            "Bijlage-naam mag max $MAX_NAAM_LENGTE characters zijn"
        }
        requireValid(mimeType.isNotBlank()) { "Bijlage-mimeType mag niet leeg zijn" }
        requireValid(mimeType.length <= MAX_MIME_LENGTE) {
            "Bijlage-mimeType mag max $MAX_MIME_LENGTE characters zijn"
        }
        requireValid(content.isNotEmpty()) { "Bijlage-inhoud mag niet leeg zijn" }
        requireValid(content.size <= MAX_CONTENT_BYTES) {
            "Bijlage-inhoud mag max ${MAX_CONTENT_BYTES / 1024 / 1024} MiB zijn"
        }
    }

    // Data classes met een ByteArray hebben standaard reference-equality op de bytes;
    // override naar contentEquals zodat equals semantisch klopt en tests deterministisch zijn.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Bijlage) return false
        return bijlageId == other.bijlageId &&
            berichtId == other.berichtId &&
            naam == other.naam &&
            mimeType == other.mimeType &&
            content.contentEquals(other.content)
    }

    override fun hashCode(): Int {
        var result = bijlageId.hashCode()
        result = 31 * result + berichtId.hashCode()
        result = 31 * result + naam.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }

    companion object {
        // Synchroniseer met `BijlageMetadata.naam.maxLength` en
        // `BijlageAanleverenRequest.naam.maxLength` in `berichtenmagazijn-api.yaml`,
        // en met `@Column(length = ...)` in `BijlageEntity`.
        const val MAX_NAAM_LENGTE = 255

        // Synchroniseer met `BijlageMetadata.mimeType.maxLength` en
        // `BijlageAanleverenRequest.mimeType.maxLength` in de spec, en met
        // `@Column(length = ...)` in `BijlageEntity`.
        const val MAX_MIME_LENGTE = 127

        // 25 MiB per bijlage. Postgres BYTEA staat tot ~1 GB toe; deze grens
        // voorkomt dat een test of misconfiguratie de heap opvreet en is
        // ruim genoeg voor reguliere PDF-bijlagen. Geen directe pendant in
        // de spec: `BijlageAanleverenRequest.inhoud` is base64-gecodeerd en
        // de validatie van de gedecodeerde lengte gebeurt hier.
        const val MAX_CONTENT_BYTES = 25 * 1024 * 1024
    }
}

/**
 * Metadata van een bijlage zonder de inhoud (bytes). Gebruikt in lijst- en
 * detail-responses van de Ophaal-API; consumers halen de inhoud separaat op via
 * `GET /berichten/{id}/bijlagen/{id}`.
 *
 * Dezelfde [Bijlage]-invarianten gelden voor naam en mimeType: een projection-
 * query mag geen blanco of te lange waardes opleveren (zou wijzen op corruptie
 * of een schema-mismatch).
 */
data class BijlageMetadata(
    val bijlageId: UUID,
    val naam: String,
    val mimeType: String,
) {
    init {
        requireValid(naam.isNotBlank()) { "Bijlage-naam mag niet leeg zijn" }
        requireValid(naam.length <= Bijlage.MAX_NAAM_LENGTE) {
            "Bijlage-naam mag max ${Bijlage.MAX_NAAM_LENGTE} characters zijn"
        }
        requireValid(mimeType.isNotBlank()) { "Bijlage-mimeType mag niet leeg zijn" }
        requireValid(mimeType.length <= Bijlage.MAX_MIME_LENGTE) {
            "Bijlage-mimeType mag max ${Bijlage.MAX_MIME_LENGTE} characters zijn"
        }
    }
}
