package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.min

/**
 * Pure functie voor het bepalen van de volgende-poging-tijd na een mislukte delivery.
 *
 * Exponentiële backoff: `basis * 2^pogingen`, begrensd op `plafond`. Bij `pogingenNaFout
 * >= maxPogingen` of `niet herstelbaar` geeft de functie `null` terug zodat de
 * delivery als terminal `MISLUKT` wordt gemarkeerd. Inclusief deterministische jitter
 * (0..~25%) op basis van `claimId` zodat retries gespreid worden zonder thundering-
 * herd.
 *
 * Server-side `Retry-After` (uit een 429/503-response) overrided altijd: als de
 * downstream zelf een hint geeft, respecteren we die i.p.v. eigen backoff te draaien
 * (begrensd op `plafond`).
 *
 * Pure functie i.p.v. CDI-bean: makkelijk unit-testbaar, geen mocks nodig.
 */
internal object RetryBeleid {

    fun volgendePoging(
        nu: Instant,
        pogingenNaFout: Int,
        maxPogingen: Int,
        basis: Duration,
        plafond: Duration,
        claimId: Long,
        herstelbaar: Boolean = true,
        retryAfter: Duration? = null,
    ): Instant? {
        require(pogingenNaFout >= 0) { "pogingenNaFout mag niet negatief zijn" }
        if (!herstelbaar) return null
        if (pogingenNaFout >= maxPogingen) return null

        // Server-hint heeft prioriteit; wel begrenzen tegen runaway.
        if (retryAfter != null) {
            val begrensd = min(retryAfter.toMillis(), plafond.toMillis())
            return nu.plusMillis(begrensd)
        }

        // 2^pogingen kan groot worden; clamp eerst tegen Long-overflow.
        val veelvoud = 1L shl min(pogingenNaFout, MAX_SHIFT)
        val rauwBasisMs = basis.toMillis().coerceAtLeast(1L)
        val basisVertragingMs = rauwBasisMs * veelvoud
        val begrensdMs = min(basisVertragingMs, plafond.toMillis())
        // Jitter 0..~25% deterministisch uit claimId; voorkomt dat veel parallel-failed
        // deliveries hun retries op dezelfde tick uitvoeren.
        val jitterMs = Math.floorMod(claimId, 100L) * begrensdMs / 400L
        return nu.plusMillis(begrensdMs + jitterMs)
    }

    /** Voorkomt dat `1L shl pogingen` overflow geeft bij absurd hoge waardes. */
    private const val MAX_SHIFT = 30
}

/**
 * Domeinnaam voor `UUIDv5`-deterministische CloudEvent-IDs. Vaste UUID
 * (gegenereerd, geen secret) zodat alle magazijn-instanties dezelfde
 * namespace gebruiken en hun event-ids cross-instantie reproducibel zijn.
 *
 * Reden voor deterministische id: bij retry naar dezelfde downstream
 * krijgt het CloudEvent dezelfde `id`. Een idempotente downstream kan
 * duplicates herkennen op `(source, id)` en hoeft niet de hele payload
 * te hashen.
 */
internal object CloudEventIdNamespace {
    val UUID_V5_NAMESPACE: UUID = UUID.fromString("8e7b4cb2-2d05-46ee-9a31-3a40b7f8a2f9")
}
