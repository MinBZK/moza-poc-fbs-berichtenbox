package nl.rijksoverheid.moz.berichtensessiecache.berichten

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue

enum class EventType(@get:JsonValue val value: String) {
    MAGAZIJN_BEVRAGING_GESTART("magazijn-bevraging-gestart"),
    MAGAZIJN_BEVRAGING_VOLTOOID("magazijn-bevraging-voltooid"),
    OPHALEN_GEREED("ophalen-gereed"),
    OPHALEN_FOUT("ophalen-fout"),
}

/**
 * Uitkomst van een afgeronde magazijn-bevraging. Wordt alleen gezet op
 * `MAGAZIJN_BEVRAGING_VOLTOOID`-events; voor `MAGAZIJN_BEVRAGING_GESTART`
 * is de uitkomst nog onbekend.
 */
enum class MagazijnStatus(@get:JsonValue val value: String) {
    OK("OK"),
    FOUT("FOUT"),
    TIMEOUT("TIMEOUT"),
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MagazijnEvent(
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
            EventType.MAGAZIJN_BEVRAGING_GESTART -> {
                requireNotNull(magazijnId) { "MAGAZIJN_BEVRAGING_GESTART vereist magazijnId" }
            }
            EventType.MAGAZIJN_BEVRAGING_VOLTOOID -> {
                requireNotNull(magazijnId) { "MAGAZIJN_BEVRAGING_VOLTOOID vereist magazijnId" }
                requireNotNull(status) { "MAGAZIJN_BEVRAGING_VOLTOOID vereist status" }
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
