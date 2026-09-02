package nl.rijksoverheid.moz.fbs.demopersonas

/**
 * Een demo-identiteit: wat een keuzelijst toont plus het nummer waarmee de keten hem kent. De
 * nummers zijn fictief — BSN's uit de 999-testreeks, zie `wiremock/demo-profiel/README.md`. Een
 * afnemende berichtenbox krijgt ze via het personas-endpoint en hoeft ze niet in zijn eigen
 * broncode op te nemen. Binnen de demo staan dezelfde nummers ook in de profielstubs en de
 * basisdataset; het bedieningspaneel toetst die tegen deze lijst.
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

        // De id komt in foutmeldingen en in de opstartlog terecht; een nummer als sleutel zou het
        // identificatienummer daar alsnog in zetten. Acht of negen cijfers is de vorm van een
        // KVK-nummer, BSN of RSIN; een langere reeks blijft toegestaan, want een OIN is publiek.
        require(!id.matches(Regex("[0-9]{8,9}"))) { "gebruik een naam als persona-id, geen nummer" }
        require(label.isNotBlank()) { "label mag niet leeg zijn" }

        Identificatiecheck.valideer(type, waarde)

        require(magazijnen.none { it.isBlank() }) { "leeg magazijn-OIN in de lijst" }

        require(magazijnen.distinct().size == magazijnen.size) {
            "magazijn-OIN dubbel in de lijst; dat trekt de verdeling van gegenereerde berichten scheef"
        }

        // Exhaustief, zodat een nieuwe bron een expliciete keuze afdwingt in plaats van stilzwijgend
        // de regel van keten te erven.
        when (bron) {
            // Bij `dataset` presenteert een berichtenbox de inhoud als gegenereerd; dan hoort de
            // generator er geen echte ketenberichten tussen te zetten.
            PersonaBron.DATASET -> require(magazijnen.isEmpty()) {
                "een persona met bron '${bron.wire}' hoort geen magazijnen te hebben"
            }

            PersonaBron.KETEN -> Unit
        }
    }

    /** Waarde voor de `X-Ontvanger`-header: `<TYPE>:<WAARDE>`. [Identificatiecheck] bepaalt welke types. */
    val ontvanger: String get() = "$type:$waarde"
}
