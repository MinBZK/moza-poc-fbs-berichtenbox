package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht

data class MagazijnBerichtenResponse(
    val berichten: List<Bericht> = emptyList(),
)
