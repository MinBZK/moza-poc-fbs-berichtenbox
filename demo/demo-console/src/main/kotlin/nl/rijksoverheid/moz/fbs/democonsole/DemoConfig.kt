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

        /** BSN, RSIN of KVK. De keten kent daarnaast OIN, maar dat identificeert een afzender-organisatie, geen ontvanger. */
        fun type(): String

        fun waarde(): String

        /**
         * OIN's van de organisaties waarvan deze persona berichten ontvangt; moet sporen met de
         * profielservice-voorkeuren, anders weigert het magazijn de aanlevering (403). Leeg = de
         * generator voert voor deze persona niets op; hij bestaat dan alleen om mee op te halen.
         * Elke OIN moet een `demo.magazijnen`-URL hebben, anders weigert de module te starten.
         */
        fun magazijnen(): Optional<List<String>>

        /** `keten` of `dataset`. Verplicht: een default zou stil op `keten` uitkomen. */
        fun bron(): String
    }
}
