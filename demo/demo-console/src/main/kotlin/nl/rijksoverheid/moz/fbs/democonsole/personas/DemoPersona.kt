package nl.rijksoverheid.moz.fbs.democonsole.personas

/**
 * Een demo-identiteit: wat een keuzelijst toont plus het nummer waarmee de keten hem kent. De
 * nummers zijn fictief — BSN's uit de 999-testreeks, zie `wiremock/demo-profiel/README.md`. Een
 * afnemende berichtenbox krijgt ze via het personas-endpoint en hoeft ze niet in zijn eigen
 * broncode op te nemen; binnen de demo staan ze op meer plekken (profielstubs, basisdataset),
 * die `DemoDatasetConsistentieTest` op elkaar houdt.
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
