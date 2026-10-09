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

/**
 * Na het leeggooien van de magazijnen zijn ook de sessies gewist. Een open berichtenbox krijgt bij
 * zijn volgende hartslag te horen dat zijn sessie weg is en haalt dan zelf opnieuw op — de
 * bediener hoeft niet te verversen, maar ziet de lijst wel even leeg of opnieuw laden.
 */
const val SESSIES_GEWIST_MELDING: String =
    "De sessies zijn gewist: een open berichtenbox haalt zijn berichten vanzelf opnieuw op."

/** Lukte het wissen niet, dan tonen berichtenboxen nog berichten die in geen magazijn meer staan. */
const val SESSIES_NIET_GEWIST_MELDING: String =
    "De sessies konden niet gewist worden; open berichtenboxen tonen nog de berichten van hiervoor. " +
        "Probeer het opnieuw, of gebruik 'Cache verlopen' op het tabblad Scenario's."

internal fun sessieMelding(gewist: Int?): String = if (gewist == null) SESSIES_NIET_GEWIST_MELDING else SESSIES_GEWIST_MELDING
