package nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag

import nl.rijksoverheid.moz.fbs.common.exception.requireValid
import java.time.Instant

/**
 * Leesstatus van een bericht voor één specifieke ontvanger.
 * Afwezigheid van een [BerichtStatus] (`null` op [Bericht.status]) betekent
 * "nog niet bekeken, geen map gekozen" — niet hetzelfde als `gelezen=false`
 * met een map, want bij verplaatsen naar een map heeft de ontvanger het
 * bericht expliciet aangeraakt.
 */
data class BerichtStatus(
    val gelezen: Boolean,
    val map: String?,
    val gewijzigdOp: Instant,
) {
    init {
        if (map != null) {
            requireValid(map.isNotBlank()) { "Map mag niet leeg zijn" }
            requireValid(map.length <= MAX_MAP_LENGTE) {
                "Map mag max $MAX_MAP_LENGTE characters zijn"
            }
        }
    }

    companion object {
        // Synchroniseer met `BerichtStatusInfo.map.maxLength` en
        // `BerichtStatusPatch.map.maxLength` in `berichtenmagazijn-api.yaml` en
        // met `@Column(length = ...)` in `BerichtStatusEntity`.
        const val MAX_MAP_LENGTE = 64
    }
}
