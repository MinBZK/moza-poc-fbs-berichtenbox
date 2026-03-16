package nl.rijksoverheid.moz.berichtensessiecache.magazijn

import nl.rijksoverheid.moz.berichtensessiecache.berichten.Bericht

data class MagazijnBerichtenResponse(
    val berichten: List<Bericht> = emptyList(),
)
