package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht

sealed class MagazijnResult {
    abstract val magazijnId: String
    abstract val naam: String?

    data class Success(
        override val magazijnId: String,
        override val naam: String?,
        val berichten: List<Bericht>,
    ) : MagazijnResult()

    data class Failure(
        override val magazijnId: String,
        override val naam: String?,
        val error: Throwable,
    ) : MagazijnResult()
}

/**
 * Marker-exception voor de availability-cap op magazijn-responses (zie
 * `berichtensessiecache.max-berichten-per-magazijn`). Geen subclass van
 * `WebApplicationException`/`ProcessingException` — dit is een interne signalering,
 * geen upstream-fault, en wordt door de service in een aparte foutmelding gemapt.
 */
class MagazijnResponseOverflow(message: String) : RuntimeException(message)
