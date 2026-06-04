package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

/**
 * Mapt magazijn-id (zoals door de sessiecache uitgedeeld) → base-URL, gevuld uit
 * `magazijnen.urls.<id>`-properties. Eén entry (single-magazijn) of meerdere
 * (multi-magazijn) — de map legt geen aantal vast. `client.*` begrenst de timeouts
 * op de magazijn-REST-client.
 *
 * `instances.<id>.afzenders` spiegelt dezelfde routing-config die de sessiecache-
 * library gebruikt: welke afzender-OIN(s) een magazijn serveert. De aanmeld-webhook
 * leidt hiermee uit de afzender van een CloudEvent het bron-magazijn af; de
 * properties-config is het gedeelde contract tussen beide consumers.
 */
@ConfigMapping(prefix = "magazijnen")
interface MagazijnenConfig {
    fun urls(): Map<String, String>

    fun instances(): Map<String, Instance>

    fun client(): Client

    interface Instance {
        /** OIN(s) van afzenders waarvan dit magazijn de berichten bewaart. */
        fun afzenders(): List<String>
    }

    interface Client {
        @WithDefault("PT2S")
        fun connectTimeout(): Duration

        @WithDefault("PT10S")
        fun readTimeout(): Duration
    }
}
