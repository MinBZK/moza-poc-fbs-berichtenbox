package nl.rijksoverheid.moz.fbs.magazijnregister

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithParentName
import java.util.Optional

/**
 * Config-bron van het register: `magazijnen."<OIN>".{url,naam}`. De map-key
 * ís de afzender-OIN — daarmee is de 1:1-koppeling OIN↔magazijn structureel
 * afgedwongen: een dubbele OIN kan in een properties-map niet bestaan.
 * Key- en URL-validatie gebeurt fail-fast in [ConfigMagazijnregister];
 * Bean Validation op deze interface zou door Quarkus' ArC-deployment-checker
 * als CDI-interceptor-binding op anonieme test-subklassen worden gevlagd.
 */
@ConfigMapping(prefix = "magazijnen")
internal interface MagazijnregisterConfig {

    @WithParentName
    fun inschrijvingen(): Map<String, Inschrijving>

    interface Inschrijving {
        fun url(): String
        fun naam(): Optional<String>
    }
}
