package nl.rijksoverheid.moz.fbs.democonsole

/**
 * Waarom de Berichtenbox ná een reset nog even doet alsof er niets veranderd is.
 *
 * De uitvraag houdt per organisatie bij hoe vaak die achter elkaar stukging. Na drie storingen slaat
 * hij haar een tijdje over — standaard een halve minuut — zodat één kapotte leverancier niet elke
 * ophaalronde ophoudt. Die teller zit in de uitvraag zelf en niet in het magazijn, dus het
 * terugzetten van een storing bereikt hem niet: tot dat venster om is meldt de Berichtenbox de
 * organisatie als "tijdelijk niet beschikbaar" terwijl ze allang weer antwoordt.
 *
 * Zonder deze melding ziet dat eruit als een knop die niets doet, en gaat iemand middenin een demo
 * zoeken naar iets dat niet stuk is.
 */
const val HERSTELTIJD_MELDING: String =
    "Organisaties die op storing stonden, kunnen in de Berichtenbox nog kort als 'tijdelijk niet " +
        "beschikbaar' verschijnen: de uitvraag slaat een organisatie na drie storingen een halve " +
        "minuut over voordat hij het opnieuw probeert. Wachten volstaat — daarna gaat ophalen vanzelf " +
        "weer goed."
