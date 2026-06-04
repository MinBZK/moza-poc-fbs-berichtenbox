package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

/**
 * Mapt magazijn-id (zoals door de sessiecache uitgedeeld) → base-URL, gevuld uit
 * `magazijnen.urls.<id>`-properties. Eén entry (single-magazijn) of meerdere
 * (multi-magazijn) — de map legt geen aantal vast. `client.*` begrenst de timeouts
 * op de magazijn-REST-client.
 */
@ConfigMapping(prefix = "magazijnen")
interface MagazijnenConfig {
    fun urls(): Map<String, String>

    fun client(): Client

    interface Client {
        @WithDefault("PT2S")
        fun connectTimeout(): Duration

        @WithDefault("PT10S")
        fun readTimeout(): Duration
    }
}
