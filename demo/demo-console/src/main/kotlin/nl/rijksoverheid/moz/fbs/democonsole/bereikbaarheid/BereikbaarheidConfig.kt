package nl.rijksoverheid.moz.fbs.democonsole.bereikbaarheid

import io.smallrye.config.ConfigMapping
import java.util.Optional

/** Welke componenten de console op bereikbaarheid controleert: `demo.bereikbaarheid."<component>".url`. */
@ConfigMapping(prefix = "demo")
interface BereikbaarheidConfig {

    fun bereikbaarheid(): Map<String, Component>

    interface Component {

        /**
         * Het adres van het component zelf, zonder health-pad; leeg schakelt de controle uit.
         * Optioneel en geen kale `String`: een env-var die expliciet leeg gezet wordt, laat
         * smallrye-config anders bij het booten falen met `SRCFG00040`.
         */
        fun url(): Optional<String>
    }
}
