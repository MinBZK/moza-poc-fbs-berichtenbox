package nl.rijksoverheid.moz.fbs.democonsole.omgeving

import io.smallrye.config.ConfigMapping
import java.util.Optional

/**
 * Het adres van de uitvraag zoals de *browser* het moet gebruiken. Bewust los van
 * `quarkus.rest-client.uitvraag.url`, dat de console zelf server-side aanroept en container-interne
 * DNS mag zijn: dat adres is vanuit een browser onbereikbaar. Leeg laten betekent "leid het af uit
 * de browser-locatie", wat lokaal het gewenste gedrag is.
 */
@ConfigMapping(prefix = "demo.omgeving")
interface OmgevingConfig {

    fun uitvraagBasis(): Optional<String>
}
