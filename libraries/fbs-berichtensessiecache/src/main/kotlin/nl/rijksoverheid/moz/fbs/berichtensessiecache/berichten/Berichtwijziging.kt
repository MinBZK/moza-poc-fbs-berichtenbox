package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

/**
 * Wat er in een sessie met een bericht gebeurde, zoals het over het aanmeldkanaal naar de open
 * berichtenboxen van de ontvanger gaat. Het kanaal draagt alleen de soort en de sleutel; de
 * nieuwe waarden leest de ontvangende pod zelf uit de cache.
 *
 * [kanaalsoort] is het vierde deel van het kanaalbericht. [BIJGEKOMEN] heeft bewust geen soort:
 * een nieuw bericht houdt de vorm van de vorige versie, zodat een pod op die versie het tijdens een
 * rolling update nog leest. Een kanaalbericht met `bijgekomen` als soort is daardoor onleesbaar.
 */
internal enum class Berichtwijziging(val kanaalsoort: String?) {
    /** Het bericht is aangemeld en staat nu in de lijst. */
    BIJGEKOMEN(null),

    /** De leesstatus of de map van het bericht is bijgewerkt. */
    GEWIJZIGD("gewijzigd"),

    /** Het bericht staat niet meer in de lijst: verwijderd, of na een mislukte bijwerking uit de cache gehaald. */
    VERWIJDERD("verwijderd"),
}
