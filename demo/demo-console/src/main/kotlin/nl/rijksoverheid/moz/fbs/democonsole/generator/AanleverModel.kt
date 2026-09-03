package nl.rijksoverheid.moz.fbs.democonsole.generator

/** Ontvanger zoals het aanlevercontract het verwacht: getypeerd identificatienummer. */
data class OntvangerDto(val type: String, val waarde: String)

/** Bijlage voor de aanlever-request. `inhoud` is Base64; `mimeType` moet application/pdf zijn. */
data class BijlageDto(val naam: String, val mimeType: String, val inhoud: String)

/**
 * Body voor `POST /api/v1/aanleveringen` op het magazijn. `afzender` is een kale OIN-string
 * (20 cijfers); alleen `ontvanger` is getypeerd. Velden matchen BerichtAanleverenRequest.
 */
data class AanleverVerzoek(
    val afzender: String,
    val ontvanger: OntvangerDto,
    val onderwerp: String,
    val inhoud: String,
    val publicatietijdstip: String,
    val bijlagen: List<BijlageDto>? = null,
)

/**
 * Eén aanlever-opdracht: het verzoek plus het magazijn (OIN) waar het naartoe moet. `gelezen`
 * is een demo-vlag (niet onderdeel van de aanlever-body): is die true, dan zet de console het
 * bericht ná aanlevering op gelezen, zodat de basisvulling een realistische lees-mix toont.
 */
data class AanleverOpdracht(val magazijnOin: String, val verzoek: AanleverVerzoek, val gelezen: Boolean = false)

/** Realistisch bericht-sjabloon: een onderwerp met bijpassende inhoud. */
data class Sjabloon(val onderwerp: String, val inhoud: String)

/**
 * Verzendende organisatie: één per magazijn (1:1 OIN↔magazijn). `oin` is tegelijk de
 * afzender-OIN én het magazijnId; `sjablonen` levert realistische onderwerp+inhoud-paren.
 */
data class Organisatie(val oin: String, val naam: String, val sjablonen: List<Sjabloon>)

/**
 * Vaste demo-ontvanger. `id` is de sleutel waarmee de bediener er één aanwijst; `type` is
 * BSN/KVK/RSIN en `waarde` het (geldige) nummer. `magazijnen` zijn de organisatie-OIN's waar deze
 * persona berichten van ontvangt — dit moet één-op-één sporen met de profielservice-voorkeuren,
 * anders weigert het magazijn de aanlevering (403).
 */
data class Persona(
    val id: String,
    val naam: String,
    val type: String,
    val waarde: String,
    val magazijnen: List<String>,
)

/**
 * Een persona zoals het bedieningspaneel hem aanwijst. Bewust niet de `PersonaDto` van de
 * personadienst: dat is het contract met een berichtenbox en draagt onder meer het
 * identificatienummer, dat het paneel niet nodig heeft — zo kan het ook niet in de query belanden.
 *
 * Niet-leeg zijn de velden omdat [DemoBerichtGenerator] dat van zijn persona's eist; die controle
 * staat daar en niet hier, want alleen daar valt hij bij het opstarten.
 */
data class Doelpersona(val id: String, val label: String)
