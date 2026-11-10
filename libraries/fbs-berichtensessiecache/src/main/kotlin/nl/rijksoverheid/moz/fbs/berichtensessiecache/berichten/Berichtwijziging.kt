package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

/**
 * Wat er in een sessie met een bericht gebeurde, zoals het over het aanmeldkanaal naar de open
 * berichtenboxen van de ontvanger gaat. Het kanaal draagt alleen de soort en de sleutel; de
 * nieuwe waarden leest de ontvangende pod zelf uit de cache.
 */
internal enum class Berichtwijziging {
    /** Het bericht is aangemeld en staat nu in de lijst. */
    BIJGEKOMEN,

    /** De leesstatus of de map van het bericht is bijgewerkt. */
    GEWIJZIGD,

    /** Het bericht is uit de lijst verwijderd. */
    VERWIJDERD,
}
