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
