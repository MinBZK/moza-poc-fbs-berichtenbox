package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import io.smallrye.config.ConfigMapping
import java.time.Instant
import java.util.Optional

/**
 * De App Managers waarvan de gesimuleerde magazijnen een credential aannemen, per OIN, elk met
 * zijn sleutels per `kid`. Geen enkele App Manager betekent: elk credential wordt afgewezen.
 *
 * TODO(#1215): uit het geverifieerde stelseldocument in plaats van uit de configuratie.
 */
@ConfigMapping(prefix = "simulator.rechtstreeks")
interface RechtstreeksConfig {

    fun appManagers(): Map<String, AppManager>

    /**
     * Netwerken (`adres/prefix`) van de proxies waarvan de laatste waarde in `X-Forwarded-For` het
     * client-adres is. Leeg: het adres van de directe peer.
     */
    fun vertrouwdeProxies(): Optional<List<String>>

    interface AppManager {
        fun sleutels(): Map<String, Sleutel>
    }

    /** Een P-256-sleutel als base64url-coördinaten, met het moment waarop hij verloopt. */
    interface Sleutel {
        fun x(): String

        fun y(): String

        fun geldigTot(): Instant
    }
}
