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

    /** De echte magazijnen, gesleuteld op afzender-OIN: `demo.magazijnen."<OIN>".{url,database}`. */
    fun magazijnen(): Map<String, Magazijn>

    interface Magazijn {

        fun url(): String

        /**
         * De database van dit magazijn, bij de naam die de console ervoor gebruikt (`magazijn-a`).
         * Afwezig: de console kan dit magazijn niet tellen en vult het niet bij het opstarten.
         */
        fun database(): Optional<String>
    }

}
