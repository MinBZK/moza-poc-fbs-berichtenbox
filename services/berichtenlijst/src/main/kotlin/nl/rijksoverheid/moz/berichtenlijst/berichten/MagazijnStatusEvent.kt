package nl.rijksoverheid.moz.berichtenlijst.berichten

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue

enum class EventType(val value: String) {
    MAGAZIJN_STATUS("magazijn-status"),
    OPHALEN_GEREED("ophalen-gereed");

    @JsonValue
    override fun toString() = value
}

enum class MagazijnStatus {
    BEZIG, OK, FOUT, TIMEOUT
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MagazijnStatusEvent(
    val event: EventType,
    val magazijnId: String? = null,
    val naam: String? = null,
    val status: MagazijnStatus? = null,
    val aantalBerichten: Int? = null,
    val foutmelding: String? = null,
    val totaalBerichten: Int? = null,
    val geslaagd: Int? = null,
    val mislukt: Int? = null,
    val totaalMagazijnen: Int? = null,
)
