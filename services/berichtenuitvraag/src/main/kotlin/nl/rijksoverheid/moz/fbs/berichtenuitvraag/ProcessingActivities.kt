package nl.rijksoverheid.moz.fbs.berichtenuitvraag

/**
 * LDV processing-activity-ID's per type uitvraag-operatie (AVG Art. 30).
 *
 * Waarden zijn URNs onder een organisatie-eigen namespace zodat ze stabiel,
 * uniek én een geldige URI zijn — `dpl.core.processing_activity_id` MOET een
 * absolute URI zijn, anders gooit de LDV-wrapper een IllegalArgumentException
 * bij iedere request (ook met `logboekdataverwerking.enabled=false`, want de
 * `LogboekInterceptor` valideert vóór de enabled-check).
 *
 * `const val` is een compile-time vereiste voor gebruik als annotatie-argument.
 */
object ProcessingActivities {
    private const val NAMESPACE = "urn:nl:moz:fbs:verwerkingsactiviteit:berichtenuitvraag"

    const val UITVRAAG_LEZEN = "$NAMESPACE:lezen"
    const val UITVRAAG_BEHEER = "$NAMESPACE:beheer"
    const val UITVRAAG_AANMELDING = "$NAMESPACE:aanmelding"
}
