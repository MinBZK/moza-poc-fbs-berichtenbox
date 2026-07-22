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

/** Vaste demo-ontvanger. `type` is BSN/KVK/RSIN; `waarde` het (geldige) nummer. */
data class Persona(val naam: String, val type: String, val waarde: String)
