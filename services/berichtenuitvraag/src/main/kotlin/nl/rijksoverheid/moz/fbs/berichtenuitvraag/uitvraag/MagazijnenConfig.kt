package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.smallrye.config.ConfigMapping

/**
 * Mapping van decentrale magazijn-identifiers (zoals door de sessiecache
 * uitgedeeld) naar hun base-URLs. Wordt gevuld vanuit `magazijnen.urls.<id>`-
 * properties; ondersteunt zowel een single-magazijn dev-setup (één entry met
 * sleutel `default`) als een multi-magazijn productie-setup.
 */
@ConfigMapping(prefix = "magazijnen")
interface MagazijnenConfig {
    fun urls(): Map<String, String>
}
