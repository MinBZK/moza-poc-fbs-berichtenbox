package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.Optional

/**
 * Alles wat onder de prefix `demo` staat. Eén mapping voor de hele prefix: SmallRye eist dat
 * elke `demo.*`-property op een member van deze interface uitkomt, dus een tweede root ernaast
 * zou bij boot op SRCFG00050 stuklopen. `@ConfigMapping` leest map-keys mét aanhalingstekens
 * betrouwbaar; een kale `@ConfigProperty Map` doet dat niet. Spiegelt het patroon van
 * ConfigMagazijnregister in fbs-magazijnregister.
 */
@ConfigMapping(prefix = "demo")
interface DemoConfig {

    /** Magazijn-aanlever-URL's, gesleuteld op afzender-OIN: `demo.magazijnen."<OIN>".url`. */
    fun magazijnen(): Map<String, Magazijn>

    /** Demo-identiteiten, gesleuteld op id: `demo.personas.<id>.label` enzovoort. */
    fun personas(): Map<String, PersonaInstelling>

    interface Magazijn {

        fun url(): String
    }

    interface PersonaInstelling {

        /** Wat de keuzelijst toont. Dient tevens als aanhef in gegenereerde berichten. */
        fun label(): String

        /** BSN, RSIN of KVK: het type uit de `X-Ontvanger`-header. */
        fun type(): String

        fun waarde(): String

        /**
         * OIN's van de organisaties waarvan deze persona berichten ontvangt; moet sporen met de
         * profielservice-voorkeuren, anders weigert het magazijn de aanlevering (403). Leeg = de
         * generator voert voor deze persona niets op; hij bestaat dan alleen om mee op te halen.
         */
        fun magazijnen(): Optional<List<String>>

        @WithDefault("keten")
        fun bron(): String
    }
}
