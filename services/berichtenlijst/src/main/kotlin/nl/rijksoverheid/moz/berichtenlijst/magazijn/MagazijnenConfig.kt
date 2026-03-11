package nl.rijksoverheid.moz.berichtenlijst.magazijn

import io.smallrye.config.ConfigMapping
import java.util.Optional

@ConfigMapping(prefix = "magazijnen")
interface MagazijnenConfig {
    fun instances(): Map<String, MagazijnInstance>

    interface MagazijnInstance {
        fun url(): String
        fun naam(): Optional<String>
    }
}
