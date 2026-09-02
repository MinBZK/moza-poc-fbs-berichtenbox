package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.smallrye.config.ConfigMapping
import java.util.Optional

/**
 * Toxiproxy-instanties uit config: `demo.toxiproxy."<proxy>".{url,listen,upstream}`. Lokaal wijzen
 * alle proxies naar één instantie; op ZAD staat elke stroom achter zijn eigen Toxiproxy vóór zijn
 * upstream. `@ConfigMapping` leest map-keys mét aanhalingstekens betrouwbaar; een kale
 * `@ConfigProperty Map` doet dat niet.
 *
 * Alle drie de waarden zijn optioneel, niet een kale `String`: een env-var die expliciet leeg gezet
 * wordt (`TOXIPROXY_MAGAZIJN_A_URL=`) levert bij smallrye-config anders `SRCFG00040` op — een lege
 * waarde geldt daar als "niet gezet", en een niet-optionele `String`-mapping faalt daar hard op bij
 * het booten in plaats van een lege waarde door te geven.
 */
@ConfigMapping(prefix = "demo")
interface ToxiproxyConfig {

    fun toxiproxy(): Map<String, Instantie>

    interface Instantie {

        /** Admin-API van de instantie waar deze proxy op staat; leeg schakelt de proxy uit. */
        fun url(): Optional<String>

        /**
         * Waar de proxy zelf luistert, als `host:poort`. Nodig omdat de console de proxy op ZAD
         * zelf aanmaakt; [ProxyBootstrap] legt uit waarom daar geen `proxies.json` staat.
         */
        fun listen(): Optional<String>

        /** Waar de proxy naartoe stuurt, als `host:poort`. Op ZAD een adres met `$DEPLOYMENT_NAME`. */
        fun upstream(): Optional<String>
    }
}
