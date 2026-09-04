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
data class Organisatie(val oin: String, val naam: String, val sjablonen: List<Sjabloon>) {

    init {
        // Zonder sjablonen valt er niets te kiezen en klapt de generator om op `nextInt(0)` — een
        // HTTP 500 met "bound must be positive" midden in een demonstratie. Liever hier: een
        // organisatie zonder sjablonen is in elke context onbruikbaar, niet alleen in de generator.
        require(sjablonen.isNotEmpty()) { "organisatie $naam ($oin) heeft geen sjablonen" }
    }
}

/**
 * Een persona zoals het bedieningspaneel hem aanwijst: alleen waarmee je hem kiest en wat je van
 * hem ziet. Geen demo-identiteit, maar een projectie daarvan — het identificatienummer dat de
 * identiteit draagt blijft zo uit de generator-API en kan niet in een query belanden.
 *
 * Dit type dwingt niets af; de velden zijn niet leeg omdat [DemoBerichtGenerator.doelgroep] de
 * enige plek is waar het ontstaat.
 */
data class Doelpersona(val id: String, val label: String)
