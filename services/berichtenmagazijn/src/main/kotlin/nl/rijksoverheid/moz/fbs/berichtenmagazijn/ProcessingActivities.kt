package nl.rijksoverheid.moz.fbs.berichtenmagazijn

/**
 * Stabiele identifiers van verwerkingsactiviteiten van het berichtenmagazijn,
 * gebruikt door de `@Logboek`-annotatie op resource-methodes om events naar
 * het Logboek Dataverwerkingen te schrijven.
 *
 * De waardes zijn op dit moment placeholders (`https://register.example.com/...`)
 * omdat het verwerkingenregister nog niet is gepubliceerd; consumenten van het
 * LDV moeten de definitieve URL's gebruiken zodra die zijn afgegeven (zie
 * issue #59). Hier centraal opgeslagen zodat één vindplaats volstaat.
 *
 * `const val` is een compile-time vereiste voor gebruik als annotatie-argument.
 */
object ProcessingActivities {
    private const val REGISTER_BASE = "https://register.example.com/verwerkingen"

    const val MAGAZIJN_AANLEVEREN = "$REGISTER_BASE/berichtenmagazijn-aanleveren"
    const val MAGAZIJN_OPHALEN = "$REGISTER_BASE/berichtenmagazijn-ophalen"
    const val MAGAZIJN_BEHEER = "$REGISTER_BASE/berichtenmagazijn-beheer"
}
