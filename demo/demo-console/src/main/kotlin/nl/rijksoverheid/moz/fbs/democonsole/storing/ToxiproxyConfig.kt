package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.smallrye.config.ConfigMapping

/**
 * Toxiproxy-instanties uit config: `demo.toxiproxy."<proxy>".url`. Lokaal wijzen alle proxies naar
 * één instantie; op ZAD staat elke stroom achter zijn eigen Toxiproxy vóór zijn upstream, omdat
 * een ZAD-component precies één poort publiceert. `@ConfigMapping` leest map-keys mét
 * aanhalingstekens betrouwbaar; een kale `@ConfigProperty Map` doet dat niet.
 */
@ConfigMapping(prefix = "demo")
interface ToxiproxyConfig {

    fun toxiproxy(): Map<String, Instantie>

    interface Instantie {

        fun url(): String
    }
}
