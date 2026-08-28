package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import io.smallrye.config.ConfigMapping

/**
 * Config-bron van de set die de simulator voorstelt: `magazijnsimulator.magazijnen."<OIN>".naam`.
 * De map-key ís de OIN, net als in het magazijnregister van de uitvraag — daarmee kan een OIN
 * per constructie niet twee keer voorkomen, en zijn beide kanten uit één generator-artefact te
 * vullen zonder dat ze uit elkaar kunnen lopen.
 *
 * Key-validatie gebeurt fail-fast in [GesimuleerdeMagazijnen]; Bean Validation op deze interface
 * zou door Quarkus' ArC-deployment-checker als CDI-interceptor-binding op anonieme
 * test-subklassen worden gevlagd.
 */
@ConfigMapping(prefix = "magazijnsimulator")
interface MagazijnSimulatorConfig {

    fun magazijnen(): Map<String, Inschrijving>

    interface Inschrijving {
        fun naam(): String
    }
}
