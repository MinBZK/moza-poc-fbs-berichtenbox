package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import nl.rijksoverheid.moz.fbs.common.exception.requireValid
import java.util.UUID

/**
 * Mogelijke statussen van een outbox-rij in `publicatie_deliveries`.
 *
 * Geen `BEZIG`-tussenstatus: de claim-adapter gebruikt `SELECT ... FOR UPDATE
 * SKIP LOCKED` zodat het row-level lock binnen de claim-transactie de
 * exclusiviteit garandeert. Na commit is de status terminal (`GEPUBLICEERD`/
 * `MISLUKT`) of `TE_PUBLICEREN` (met opgehoogde `pogingen` en nieuwe
 * `volgende_poging`).
 */
internal enum class DeliveryStatus {
    TE_PUBLICEREN,
    GEPUBLICEERD,
    MISLUKT,
}

/**
 * Value class voor de naam van een downstream-doel (sleutel in
 * [PublicatieConfig.downstreams]). Voorkomt dat een rauwe URL of een
 * `berichtId.toString()` per ongeluk wordt doorgegeven waar een doel-key
 * verwacht wordt — dit zou compileren met `String` maar runtime falen.
 *
 * Pattern matcht de DB-kolom `publicatie_deliveries.doel VARCHAR(64)` en sluit
 * leestekens uit die met config-property-keys interfereren.
 */
@JvmInline
value class Publicatiedoel(val key: String) {
    init {
        requireValid(KEY_PATTERN.matches(key)) {
            "Doel-key '$key' voldoet niet aan pattern $KEY_PATTERN"
        }
    }

    override fun toString(): String = key

    companion object {
        val KEY_PATTERN: Regex = Regex("^[a-z][a-z0-9-]{0,63}$")
    }
}

/**
 * Domeinrepresentatie van een geclaimde outbox-rij die door [PublicatieStream]
 * wordt afgeleverd. Bewust géén JPA-entity zodat de stream-bean en
 * [PublicatieClaimer]-port DB-onafhankelijk blijven.
 *
 * `claimId` is de surrogate primary key van de delivery-rij; alleen door
 * [PublicatieClaimer.markeerGeslaagd] / [PublicatieClaimer.markeerMislukt]
 * gebruikt om de juiste rij bij te werken.
 */
data class PublicatieClaim(
    val claimId: Long,
    val berichtId: UUID,
    val doel: Publicatiedoel,
    val pogingen: Int,
) {
    init {
        requireValid(claimId > 0) { "claimId moet positief zijn (kreeg $claimId)" }
        requireValid(pogingen >= 0) { "pogingen mag niet negatief zijn (kreeg $pogingen)" }
    }
}
