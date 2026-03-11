package nl.rijksoverheid.moz.berichtenlijst.magazijn

import nl.rijksoverheid.moz.berichtenlijst.berichten.Bericht

data class MagazijnBerichtenResponse(
    val berichten: List<Bericht> = emptyList(),
    val totalElements: Long = 0,
    val totalPages: Int = 0,
)
