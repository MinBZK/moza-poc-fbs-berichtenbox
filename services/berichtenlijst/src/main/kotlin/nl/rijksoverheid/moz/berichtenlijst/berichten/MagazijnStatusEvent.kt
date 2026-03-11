package nl.rijksoverheid.moz.berichtenlijst.berichten

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MagazijnStatusEvent(
    val event: String,
    val magazijnId: String? = null,
    val naam: String? = null,
    val status: String? = null,
    val aantalBerichten: Int? = null,
    val foutmelding: String? = null,
    val totaalBerichten: Int? = null,
    val geslaagd: Int? = null,
    val mislukt: Int? = null,
    val totaalMagazijnen: Int? = null,
)
