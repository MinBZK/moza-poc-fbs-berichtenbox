package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonValue

enum class EventType(@get:JsonValue val value: String) {
    MAGAZIJN_BEVRAGING_GESTART("magazijn-bevraging-gestart"),
    MAGAZIJN_BEVRAGING_VOLTOOID("magazijn-bevraging-voltooid"),
    OPHALEN_GEREED("ophalen-gereed"),
    OPHALEN_FOUT("ophalen-fout"),
}

/**
 * Uitkomst van een mislukte magazijn-bevraging. `OK` ontbreekt hier bewust: een geslaagde
 * bevraging is een eigen type ([MagazijnBevragingGeslaagd]) met [STATUS_GESLAAGD] als
 * wire-waarde. Zo bestaat er geen samenstelling met status `OK` én een foutmelding.
 */
enum class MagazijnFoutStatus(@get:JsonValue val value: String) {
    FOUT("FOUT"),
    TIMEOUT("TIMEOUT"),
}

/** Wire-waarde van `status` bij een geslaagde bevraging — het enige statuswoord buiten [MagazijnFoutStatus]. */
const val STATUS_GESLAAGD = "OK"

/**
 * Voortgangsbericht van een ophaalronde, zoals de berichten-uitvraag het als SSE naar het
 * portaal stuurt. Per soort bericht is er een eigen type met uitsluitend de velden die dat
 * soort draagt, zodat een onvolledige of tegenstrijdige combinatie niet te construeren is.
 *
 * Het wire-formaat is een vlak JSON-object met `event` als discriminator; [JsonPropertyOrder]
 * per type pint de veldvolgorde, `NON_NULL` laat een ontbrekende `naam` weg. De stroom wordt
 * alleen geproduceerd, nooit door ons ingelezen — er is dus geen polymorfe deserialisatie.
 */
sealed interface MagazijnEvent {
    val event: EventType
}

/** Bevraging van één magazijn: het `magazijnId` is de afzender-OIN, `naam` de weergavenaam. */
sealed interface MagazijnBevraging : MagazijnEvent {
    val magazijnId: String
    val naam: String?
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder("event", "magazijnId", "naam")
data class MagazijnBevragingGestart(
    override val magazijnId: String,
    override val naam: String?,
) : MagazijnBevraging {
    override val event: EventType get() = EventType.MAGAZIJN_BEVRAGING_GESTART
}

/** Afgeronde bevraging van één magazijn; de uitkomst bepaalt het concrete type. */
sealed interface MagazijnBevragingVoltooid : MagazijnBevraging {
    override val event: EventType get() = EventType.MAGAZIJN_BEVRAGING_VOLTOOID
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder("event", "magazijnId", "naam", "status", "aantalBerichten")
data class MagazijnBevragingGeslaagd(
    override val magazijnId: String,
    override val naam: String?,
    val aantalBerichten: Int,
) : MagazijnBevragingVoltooid {
    val status: String get() = STATUS_GESLAAGD
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder("event", "magazijnId", "naam", "status", "foutmelding")
data class MagazijnBevragingMislukt(
    override val magazijnId: String,
    override val naam: String?,
    val status: MagazijnFoutStatus,
    val foutmelding: String,
) : MagazijnBevragingVoltooid

/** Afsluitend bericht van een geslaagde ophaalronde; de tellers dekken alle bevraagde magazijnen. */
@JsonPropertyOrder("event", "totaalBerichten", "geslaagd", "mislukt", "totaalMagazijnen")
data class OphalenGereed(
    val totaalBerichten: Int,
    val geslaagd: Int,
    val mislukt: Int,
    val totaalMagazijnen: Int,
) : MagazijnEvent {
    override val event: EventType get() = EventType.OPHALEN_GEREED
}

/**
 * Afsluitend bericht van een mislukte ophaalronde. `referentie` is het support-anker dat
 * ook in de bijbehorende foutlog staat; het staat daarnaast in de tekst van [foutmelding]
 * voor portalen die het losse veld nog niet tonen.
 */
sealed interface OphalenFout : MagazijnEvent {
    override val event: EventType get() = EventType.OPHALEN_FOUT
    val foutmelding: String
    val totaalMagazijnen: Int
    val referentie: String
}

/** Ophalen strandde voordat er een magazijn bevraagd werd; er zijn dus geen uitkomst-tellers. */
@JsonPropertyOrder("event", "foutmelding", "totaalMagazijnen", "referentie")
data class OphalenMislukt(
    override val foutmelding: String,
    override val referentie: String,
) : OphalenFout {
    override val totaalMagazijnen: Int get() = 0
}

/**
 * De magazijnen zijn bevraagd, maar het opslaan van het resultaat mislukte. De tellers zijn
 * er wél: het portaal heeft de per-magazijn-uitkomsten al gezien en moet weten dat ze niet
 * bewaard zijn.
 */
@JsonPropertyOrder("event", "foutmelding", "geslaagd", "mislukt", "totaalMagazijnen", "referentie")
data class OpslaanMislukt(
    override val foutmelding: String,
    val geslaagd: Int,
    val mislukt: Int,
    override val totaalMagazijnen: Int,
    override val referentie: String,
) : OphalenFout
