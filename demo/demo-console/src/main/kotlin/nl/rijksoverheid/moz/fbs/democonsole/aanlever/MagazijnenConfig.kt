package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import io.smallrye.config.ConfigMapping

/**
 * Magazijn-aanlever-URL's uit config: `demo.magazijnen."<OIN>".url`. `@ConfigMapping` leest
 * de map-keys mét aanhalingstekens betrouwbaar; een kale `@ConfigProperty Map` doet dat niet.
 * Spiegelt het patroon van ConfigMagazijnregister in fbs-magazijnregister.
 */
@ConfigMapping(prefix = "demo")
interface MagazijnenConfig {

    fun magazijnen(): Map<String, Magazijn>

    interface Magazijn {

        fun url(): String
    }
}
