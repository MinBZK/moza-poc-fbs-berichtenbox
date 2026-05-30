package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.smallrye.config.ConfigMapping

/**
 * Mapt magazijn-id (zoals door de sessiecache uitgedeeld) → base-URL, gevuld uit
 * `magazijnen.urls.<id>`-properties. Eén entry (single-magazijn) of meerdere
 * (multi-magazijn) — de map legt geen aantal vast.
 */
@ConfigMapping(prefix = "magazijnen")
interface MagazijnenConfig {
    fun urls(): Map<String, String>
}
