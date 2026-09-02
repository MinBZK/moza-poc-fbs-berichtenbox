package nl.rijksoverheid.moz.fbs.democonsole.omgeving

import io.smallrye.config.ConfigMapping
import java.util.Optional

/** Wat deze omgeving de statische pagina's over zichzelf te vertellen heeft. */
@ConfigMapping(prefix = "demo.omgeving")
interface OmgevingConfig {

    /**
     * Het adres van de uitvraag zoals de *browser* het moet gebruiken. Bewust los van
     * `quarkus.rest-client.uitvraag.url`, dat de console zelf server-side aanroept en
     * container-interne DNS mag zijn: dat adres is vanuit een browser onbereikbaar. Leeg laten
     * betekent "leid het af uit de browser-locatie", wat lokaal het gewenste gedrag is.
     */
    fun uitvraagBasis(): Optional<String>

    /**
     * Het adres van de berichtenbox zoals de *browser* het moet gebruiken, voor het frame in het
     * paneel. Leeg laten betekent "hij staat op deze origin", wat lokaal klopt: de demo-proxy zet
     * de berichtenbox en dit paneel achter hetzelfde adres. Op een gedeelde omgeving is er geen
     * proxy en draagt elk component zijn eigen hostnaam, dus daar hoort hier de volledige URL.
     */
    fun berichtenboxUrl(): Optional<String>

    /**
     * Kan deze omgeving bij de sessiecache van de uitvraag? Lokaal deelt de console het
     * compose-netwerk met Redis; op een gedeelde omgeving staat Redis in een ander project, en
     * verkeer daarheen is er alleen als er een netwerkregel voor geschreven is.
     */
    fun sessiecache(): Boolean

    /**
     * Kent deze omgeving een magazijn-simulator? Uit de configuratie en niet uit een geslaagde
     * uitlezing: anders is "niet ingericht" niet te onderscheiden van "niet kunnen lezen", en
     * verdwijnen de knoppen juist wanneer er iets stuk is.
     */
    fun simulator(): Boolean
}
