package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithParentName
import java.util.Optional
import java.util.OptionalInt

/**
 * Config-bron van de set die de simulator voorstelt:
 * `magazijnsimulator.magazijnen."<OIN>".{naam,index,gedrag}`.
 *
 * De map-key ís de OIN, net als in het magazijnregister van de uitvraag — daarmee kan een OIN per
 * constructie niet twee keer voorkomen, en zijn beide kanten uit één generator-artefact te vullen
 * zonder dat ze uit elkaar kunnen lopen.
 *
 * Key- en waardevalidatie gebeurt fail-fast in [MagazijnConfiguratie]; Bean Validation op deze
 * interface zou door Quarkus' ArC-deployment-checker als CDI-interceptor-binding op anonieme
 * test-subklassen worden gevlagd.
 *
 * Het prefix reikt bewust tot `magazijnsimulator.magazijnen` en niet tot `magazijnsimulator`. Een
 * mapping claimt zijn hele namespace en eist dat élke sleutel eronder ergens op uitkomt; met het
 * kortere prefix zou `magazijnsimulator.beheer.token` de boot laten falen met "does not map to any
 * root".
 */
@ConfigMapping(prefix = "magazijnsimulator.magazijnen")
interface MagazijnSimulatorConfig {

    @WithParentName
    fun magazijnen(): Map<String, Inschrijving>

    interface Inschrijving {
        fun naam(): String

        /**
         * Volgnummer van dit magazijn binnen de gegenereerde set. Bepaalt zijn gedrag via de
         * vastgelegde verdeling, zodat het generatiescript alleen een nummer hoeft te schrijven en
         * de verdeling zelf op één plek staat — getest, en gelijk in elke omgeving.
         *
         * Afwezig betekent "geen plek in die verdeling", en dan gedraagt het magazijn zich normaal.
         */
        fun index(): OptionalInt

        /**
         * Overschrijft het gedrag dat bij [index] hoort. Bedoeld om in een demo bewust één magazijn
         * anders te zetten zonder de hele verdeling te verschuiven.
         */
        fun gedrag(): Optional<String>
    }
}
