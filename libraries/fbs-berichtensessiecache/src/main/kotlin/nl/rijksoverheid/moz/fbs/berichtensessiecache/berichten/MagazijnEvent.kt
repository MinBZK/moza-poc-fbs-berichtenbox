package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonValue

/** Soort voortgangsbericht; op de lijn de discriminator in het `event`-veld. */
enum class EventType(@get:JsonValue val value: String) {
    MAGAZIJN_BEVRAGING_GESTART("magazijn-bevraging-gestart"),
    MAGAZIJN_BEVRAGING_VOLTOOID("magazijn-bevraging-voltooid"),
    OPHALEN_GEREED("ophalen-gereed"),
    OPHALEN_FOUT("ophalen-fout"),
}

/** Uitkomst van een afgeronde magazijn-bevraging, zoals die op de lijn verschijnt. */
enum class MagazijnStatus(@get:JsonValue val value: String) {
    OK("OK"),
    FOUT("FOUT"),
    TIMEOUT("TIMEOUT"),
}

/**
 * De statussen die een mislukte bevraging kan dragen. Elke waarde wijst naar zijn
 * [MagazijnStatus]-tegenhanger, zodat er één woordenlijst voor de lijn blijft en de twee
 * niet uiteen kunnen lopen. `OK` ontbreekt hier per constructie: een geslaagde bevraging
 * is een eigen type ([MagazijnBevragingGeslaagd]).
 */
enum class MagazijnFoutStatus(val wire: MagazijnStatus) {
    FOUT(MagazijnStatus.FOUT),
    TIMEOUT(MagazijnStatus.TIMEOUT),
}

/**
 * Voortgangsbericht van een ophaalronde, zoals de berichten-uitvraag het als SSE naar het
 * portaal stuurt. Per soort bericht is er een eigen type met uitsluitend de velden die dat
 * soort draagt, zodat een onvolledige of tegenstrijdige combinatie niet te construeren is.
 *
 * Het wire-formaat is een vlak JSON-object met `event` als discriminator; [JsonPropertyOrder]
 * pint per type de veldvolgorde. De stroom wordt alleen geproduceerd, nooit door ons
 * ingelezen — er is dus geen polymorfe deserialisatie.
 */
sealed interface MagazijnEvent {
    val event: EventType
}

/** Bevraging van één magazijn: het `magazijnId` is de afzender-OIN, `naam` de weergavenaam. */
sealed interface MagazijnBevraging : MagazijnEvent {
    val magazijnId: String
    val naam: String
}

@JsonPropertyOrder("event", "magazijnId", "naam")
data class MagazijnBevragingGestart(
    override val magazijnId: String,
    override val naam: String,
) : MagazijnBevraging {
    override val event: EventType get() = EventType.MAGAZIJN_BEVRAGING_GESTART
}

/** Afgeronde bevraging van één magazijn; de uitkomst bepaalt het concrete type. */
sealed interface MagazijnBevragingVoltooid : MagazijnBevraging {
    override val event: EventType get() = EventType.MAGAZIJN_BEVRAGING_VOLTOOID
    val status: MagazijnStatus
}

@JsonPropertyOrder("event", "magazijnId", "naam", "status", "aantalBerichten")
data class MagazijnBevragingGeslaagd(
    override val magazijnId: String,
    override val naam: String,
    val aantalBerichten: Int,
) : MagazijnBevragingVoltooid {
    override val status: MagazijnStatus get() = MagazijnStatus.OK
}

@JsonPropertyOrder("event", "magazijnId", "naam", "status", "foutmelding")
data class MagazijnBevragingMislukt(
    override val magazijnId: String,
    override val naam: String,
    @get:JsonIgnore val fout: MagazijnFoutStatus,
    val foutmelding: String,
) : MagazijnBevragingVoltooid {
    override val status: MagazijnStatus get() = fout.wire
}

/**
 * Afsluitend bericht van een geslaagde ophaalronde; de tellers dekken alle bevraagde magazijnen.
 *
 * De tellers worden hier bewust niet gevalideerd. De grenzen liggen op de aggregatiestatus
 * die van dezelfde waarden wordt gebouwd, vlak vóór dit bericht en vóórdat er iets in de
 * cache belandt. Een tweede check hier zou pas kunnen aanslaan als die eerste al door was,
 * en dan midden in een geopende stroom: het bericht is de laatste stap ná een geslaagde
 * opslag, dus een throw zou de gebruiker een al veilig opgeslagen resultaat afnemen.
 */
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
 *
 * De twee concrete typen onderscheiden zich naar de fase waarin het misging, niet naar de
 * oorzaak: alleen ná de bevraging zijn er uitkomst-tellers om te melden.
 */
sealed interface OphalenFout : MagazijnEvent {
    override val event: EventType get() = EventType.OPHALEN_FOUT
    val foutmelding: String
    val totaalMagazijnen: Int
    val referentie: String
}

/**
 * Het ophalen strandde voordat er één magazijn bevraagd was — een onbruikbare
 * magazijn-configuratie, of een lege magazijn-set waarvan het resultaat niet opgeslagen kon
 * worden. `totaalMagazijnen` is daarom 0: dat telt de magazijnen die daadwerkelijk bevraagd
 * zijn, niet hoeveel er hadden kunnen zijn.
 */
@JsonPropertyOrder("event", "foutmelding", "totaalMagazijnen", "referentie")
data class OphalenMisluktVoorBevraging(
    override val foutmelding: String,
    override val referentie: String,
) : OphalenFout {
    override val totaalMagazijnen: Int get() = 0
}

/**
 * De magazijnen zijn bevraagd, maar het opslaan van het resultaat mislukte. De tellers zijn
 * er wél: het portaal heeft de per-magazijn-uitkomsten al gezien en moet weten dat ze niet
 * bewaard zijn. Zie [OphalenGereed] voor de reden dat de tellers hier niet gevalideerd worden;
 * dit bericht is bovendien zelf het herstelpad, dus een throw zou de gebruiker helemáál geen
 * afsluitend bericht opleveren.
 */
@JsonPropertyOrder("event", "foutmelding", "geslaagd", "mislukt", "totaalMagazijnen", "referentie")
data class OphalenMisluktNaBevraging(
    override val foutmelding: String,
    val geslaagd: Int,
    val mislukt: Int,
    override val totaalMagazijnen: Int,
    override val referentie: String,
) : OphalenFout
