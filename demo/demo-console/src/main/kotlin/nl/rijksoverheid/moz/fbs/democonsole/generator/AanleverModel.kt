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
 * en `map` zijn demo-vlaggen (niet onderdeel van de aanlever-body): de console zet het bericht
 * ná aanlevering op gelezen en/of in die map. Een map is een keuze van de ontvanger en geen
 * eigenschap die de afzender meestuurt; zonder deze vlag begint elke persona met alles in
 * Postvak IN en valt er over mappen niets te laten zien.
 */
data class AanleverOpdracht(
    val magazijnOin: String,
    val verzoek: AanleverVerzoek,
    val gelezen: Boolean = false,
    val map: String? = null,
) {
    init {
        // Een lege of witte naam zou na aanlevering een wis-patch of een 400 opleveren, en die
        // telt dan stil als mislukte markering in plaats van als fout in de dataset.
        require(map == null || map.isNotBlank()) { "een demo-map heeft een naam" }
    }

    /** Of er na aanlevering nog een status-patch nodig is. */
    val vraagtStatus: Boolean get() = gelezen || map != null
}

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
 * identiteit draagt blijft zo uit de keuzelijst en kan niet in een query belanden.
 *
 * Dit type dwingt niets af. Wat [DemoBerichtGenerator.doelgroep] oplevert heeft niet-lege velden
 * omdat `DemoPersona` een lege id en een leeg label weigert.
 */
data class Doelpersona(val id: String, val label: String)
