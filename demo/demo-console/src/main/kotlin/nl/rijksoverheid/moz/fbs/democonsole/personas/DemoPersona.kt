package nl.rijksoverheid.moz.fbs.democonsole.personas

import nl.rijksoverheid.moz.fbs.democonsole.generator.Identificatiecheck

/**
 * Een demo-identiteit: wat de keuzelijst toont plus het nummer waarmee de keten hem kent. Het
 * nummer staat hier en in de configuratie van de demo, niet in de proeftuin — dat is een publiek
 * repository, en een identificatienummer dat daar in de historie belandt is niet terug te nemen.
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

        Identificatiecheck.valideer(type, waarde)
    }

    /** Waarde voor de `X-Ontvanger`-header, die `^(BSN|RSIN|KVK|OIN):[0-9]+$` verlangt. */
    val ontvanger: String get() = "$type:$waarde"
}
