package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

/**
 * Timeouts op de magazijn-REST-client van [MagazijnRouter] (bijlage-/write-pad).
 * Eigen prefix, los van `magazijnen.` (dat is de register-map met OIN-keys) en
 * los van `magazijn-client.*` (het read-aggregatie-pad van de sessiecache):
 * bijlage-proxy en aggregatie hebben los timeout-beleid.
 */
@ConfigMapping(prefix = "magazijn-router")
interface MagazijnRouterConfig {

    @WithDefault("PT2S")
    fun connectTimeout(): Duration

    @WithDefault("PT10S")
    fun readTimeout(): Duration
}
