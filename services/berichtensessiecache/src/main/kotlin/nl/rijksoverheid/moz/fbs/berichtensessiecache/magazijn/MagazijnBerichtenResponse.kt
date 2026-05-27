package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

data class MagazijnBerichtenResponse(
    val berichten: List<MagazijnBericht> = emptyList(),
)
