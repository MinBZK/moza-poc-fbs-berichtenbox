package nl.rijksoverheid.moz.berichtensessiecache.berichten

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue

enum class EventType(@get:JsonValue val value: String) {
    MAGAZIJN_STATUS("magazijn-status"),
    OPHALEN_GEREED("ophalen-gereed"),
    OPHALEN_FOUT("ophalen-fout"),
}

enum class MagazijnStatus(@get:JsonValue val value: String) {
    BEZIG("BEZIG"),
    OK("OK"),
    FOUT("FOUT"),
    TIMEOUT("TIMEOUT"),
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
) {
    init {
        when (event) {
            EventType.MAGAZIJN_STATUS -> {
                requireNotNull(magazijnId) { "MAGAZIJN_STATUS vereist magazijnId" }
                requireNotNull(status) { "MAGAZIJN_STATUS vereist status" }
            }
            EventType.OPHALEN_GEREED -> {
                requireNotNull(totaalBerichten) { "OPHALEN_GEREED vereist totaalBerichten" }
                requireNotNull(totaalMagazijnen) { "OPHALEN_GEREED vereist totaalMagazijnen" }
            }
            EventType.OPHALEN_FOUT -> {
                requireNotNull(foutmelding) { "OPHALEN_FOUT vereist foutmelding" }
                requireNotNull(totaalMagazijnen) { "OPHALEN_FOUT vereist totaalMagazijnen" }
            }
        }
    }
}
