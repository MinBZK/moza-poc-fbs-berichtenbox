package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.smallrye.config.ConfigMapping
import java.util.Optional

/**
 * Toxiproxy-instanties uit config: `demo.toxiproxy."<proxy>".url`. Lokaal wijzen alle proxies naar
 * één instantie; op ZAD staat elke stroom achter zijn eigen Toxiproxy vóór zijn upstream, omdat
 * een ZAD-component precies één poort publiceert. `@ConfigMapping` leest map-keys mét
 * aanhalingstekens betrouwbaar; een kale `@ConfigProperty Map` doet dat niet.
 *
 * `url()` is optioneel, niet een kale `String`: een env-var die expliciet leeg gezet wordt
 * (`TOXIPROXY_MAGAZIJN_A_URL=`) levert bij smallrye-config anders `SRCFG00040` op — een lege waarde
 * geldt daar als "niet gezet", en een niet-optionele `String`-mapping faalt daar hard op bij het
 * booten in plaats van een lege waarde door te geven.
 */
@ConfigMapping(prefix = "demo")
interface ToxiproxyConfig {

    fun toxiproxy(): Map<String, Instantie>

    interface Instantie {

        fun url(): Optional<String>
    }
}
