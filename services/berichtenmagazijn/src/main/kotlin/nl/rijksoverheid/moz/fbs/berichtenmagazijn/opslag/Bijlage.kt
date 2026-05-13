package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.exception.requireValid
import java.util.UUID

/**
 * Volledig domeinmodel van een bijlage, inclusief inhoud (bytes).
 * Wordt gebruikt door de Aanlever-flow (opslaan) en door `getBijlage` (uitlevering).
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
        const val MAX_NAAM_LENGTE = 255
        const val MAX_MIME_LENGTE = 127

        // 25 MiB per bijlage. Postgres BYTEA staat tot ~1 GB toe; deze grens is pragmatisch
        // voor PoC-doeleinden en voorkomt dat een test of misconfiguratie de heap opvreet.
        const val MAX_CONTENT_BYTES = 25 * 1024 * 1024
    }
}

/**
 * Metadata van een bijlage zonder de inhoud (bytes). Gebruikt in lijst- en
 * detail-responses van de Ophaal-API; consumers halen de inhoud separaat op via
 * `GET /berichten/{id}/bijlagen/{id}`.
 */
data class BijlageMetadata(
    val bijlageId: UUID,
    val naam: String,
    val mimeType: String,
)
