package nl.rijksoverheid.moz.fbs.democonsole.generator

/** Ontvanger zoals het aanlevercontract het verwacht: getypeerd identificatienummer. */
data class OntvangerDto(val type: String, val waarde: String)

/**
 * Body voor `POST /api/v1/berichten` op het magazijn. `afzender` is een kale OIN-string
 * (20 cijfers); alleen `ontvanger` is getypeerd. Velden matchen BerichtAanleverenRequest.
 */
data class AanleverVerzoek(
    val afzender: String,
    val ontvanger: OntvangerDto,
    val onderwerp: String,
    val inhoud: String,
    val publicatietijdstip: String,
)

/** Eén aanlever-opdracht: het verzoek plus het magazijn (OIN) waar het naartoe moet. */
data class AanleverOpdracht(val magazijnOin: String, val verzoek: AanleverVerzoek)

/**
 * Verzendende organisatie: één per magazijn (1:1 OIN↔magazijn). `oin` is tegelijk de
 * afzender-OIN én het magazijnId; `onderwerpen` levert realistische onderwerpregels.
 */
data class Organisatie(val oin: String, val naam: String, val onderwerpen: List<String>)

/**
 * Vaste demo-ontvanger. `type` is BSN/KVK/RSIN; `waarde` het (geldige) nummer. `magazijnen`
 * zijn de organisatie-OIN's waar deze persona berichten van ontvangt — dit moet één-op-één
 * sporen met de profielservice-voorkeuren, anders weigert het magazijn de aanlevering (403).
 */
data class Persona(val naam: String, val type: String, val waarde: String, val magazijnen: List<String>)
