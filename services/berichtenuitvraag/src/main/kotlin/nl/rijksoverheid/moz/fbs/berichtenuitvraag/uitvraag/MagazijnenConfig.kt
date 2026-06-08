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
 * `instances.<id>.afzenders` gebruikt dezelfde property-sleutel-conventie als de
 * sessiecache-library (welke afzender-OIN(s) een magazijn serveert); beide lezen die
 * sleutels onafhankelijk via hun eigen ConfigMapping — gedeeld is de conventie, niet
 * het type. De aanmeld-webhook leidt hiermee uit de afzender van een CloudEvent het
 * bron-magazijn af.
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
