package nl.rijksoverheid.moz.fbs.berichtenmagazijn

/**
 * Stabiele identifiers van verwerkingsactiviteiten van het berichtenmagazijn,
 * gebruikt door de `@Logboek`-annotatie op resource-methodes om events naar
 * het Logboek Dataverwerkingen te schrijven.
 *
 * De waardes zijn URNs onder een organisatie-eigen namespace (`urn:nl:moz:fbs:...`)
 * zodat ze nu al stabiel, uniek én een geldige URI zijn — een resolvable URL
 * naar het verwerkingenregister kan later toegevoegd worden zonder dat
 * de identifier zelf hoeft te wijzigen. Niet-resolvende `example.com`-URLs
 * zouden de juridische koppeling (AVG art. 30) breken voor tooling die de
 * URI dereferenceert.
 *
 * `const val` is een compile-time vereiste voor gebruik als annotatie-argument.
 */
object ProcessingActivities {
    private const val NAMESPACE = "urn:nl:moz:fbs:verwerkingsactiviteit:berichtenmagazijn"

    const val MAGAZIJN_AANLEVEREN = "$NAMESPACE:aanleveren"
    const val MAGAZIJN_OPHALEN = "$NAMESPACE:ophalen"
    const val MAGAZIJN_BEHEER = "$NAMESPACE:beheer"
    const val MAGAZIJN_RETENTIE = "$NAMESPACE:retentie"
}
