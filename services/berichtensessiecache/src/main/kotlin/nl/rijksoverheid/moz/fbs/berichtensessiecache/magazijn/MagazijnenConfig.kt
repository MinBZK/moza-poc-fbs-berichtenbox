package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.smallrye.config.ConfigMapping
import java.util.Optional

@ConfigMapping(prefix = "magazijnen")
interface MagazijnenConfig {
    fun instances(): Map<String, MagazijnInstance>

    interface MagazijnInstance {
        fun url(): String
        fun naam(): Optional<String>

        /**
         * OIN(s) van afzenders die dit magazijn serveert. Gebruikt door
         * MagazijnResolver om dienstvoorkeuren te koppelen aan magazijnen.
         * Mag niet leeg zijn — fail-fast in [MagazijnClientFactory.init] per
         * magazijn-ID. Bean Validation (`@NotEmpty`) op deze interface zou
         * door Quarkus' ArC-deployment-checker als CDI-interceptor-binding
         * op anonieme test-subklassen worden gevlagd; daarom blijft de check
         * imperatief in de factory.
         */
        fun afzenders(): List<String>
    }
}
