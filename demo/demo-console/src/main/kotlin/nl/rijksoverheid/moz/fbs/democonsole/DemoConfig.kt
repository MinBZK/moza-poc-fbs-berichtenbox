package nl.rijksoverheid.moz.fbs.democonsole

import io.smallrye.config.ConfigMapping
import java.util.Optional

/**
 * Alles wat onder de prefix `demo` staat. Elke `demo.*`-property moet op een member van een
 * mapping uitkomen, anders weigert SmallRye de boot met SRCFG00050 — daarom staat losse
 * demo-configuratie die hier niet past buiten de prefix. `@ConfigMapping` leest map-keys mét
 * aanhalingstekens betrouwbaar; een kale `@ConfigProperty Map` doet dat niet. Spiegelt het
 * patroon van ConfigMagazijnregister in fbs-magazijnregister.
 */
@ConfigMapping(prefix = "demo")
interface DemoConfig {

    /** Magazijn-aanlever-URL's, gesleuteld op afzender-OIN: `demo.magazijnen."<OIN>".url`. */
    fun magazijnen(): Map<String, Magazijn>

    interface Magazijn {

        fun url(): String
    }

}
