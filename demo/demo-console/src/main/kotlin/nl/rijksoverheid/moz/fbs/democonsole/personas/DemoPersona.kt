package nl.rijksoverheid.moz.fbs.democonsole.personas

/**
 * Een demo-identiteit: wat een keuzelijst toont plus het nummer waarmee de keten hem kent.
 * De nummers zijn fictief — BSN's uit de 999-testreeks, zie `wiremock/demo-profiel/README.md` —
 * en staan uitsluitend in de configuratie van deze module, zodat een afnemende berichtenbox ze
 * via het endpoint krijgt en niet in zijn eigen broncode hoeft op te nemen.
 */
data class DemoPersona(
    val id: String,
    val label: String,
    val type: String,
    val waarde: String,
    val magazijnen: List<String>,
    val bron: PersonaBron,
) {

    init {
        require(id.isNotBlank()) { "id mag niet leeg zijn" }
        require(label.isNotBlank()) { "label mag niet leeg zijn" }
        require(type.isNotBlank()) { "type mag niet leeg zijn" }

        Identificatiecheck.valideer(type, waarde)

        magazijnen.forEach { require(it.isNotBlank()) { "leeg magazijn-OIN in de lijst: $magazijnen" } }

        // Een dataset-persona toont gegenereerde berichten; laat de generator er dan ook geen
        // echte ketenberichten voor opvoeren, want die zouden onzichtbaar blijven.
        require(bron == PersonaBron.KETEN || magazijnen.isEmpty()) {
            "een persona met bron '${bron.name.lowercase()}' hoort geen magazijnen te hebben"
        }
    }

    /** Waarde voor de `X-Ontvanger`-header: `<TYPE>:<WAARDE>`. Alleen BSN, RSIN en KVK komen erdoor. */
    val ontvanger: String get() = "$type:$waarde"
}
