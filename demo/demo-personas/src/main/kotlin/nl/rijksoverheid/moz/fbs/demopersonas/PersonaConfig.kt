package nl.rijksoverheid.moz.fbs.demopersonas

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithParentName
import java.util.Optional

/**
 * De ingerichte demo-identiteiten. De waarden staan in `META-INF/microprofile-config.properties`
 * van deze module, zodat de personadienst en de demo-console dezelfde lijst lezen zonder dat iemand
 * hem twee keer onderhoudt. Dat bestand draagt ordinal 100; een `application.properties` van de
 * applicatie zelf draagt 250 en wint dus van deze waarden — wie een persona wil overschrijven kan
 * dat, wie er per ongeluk een kopie naast zet merkt er niets van.
 */
@ConfigMapping(prefix = "demo.personas")
interface PersonaConfig {

    /** Gesleuteld op id: `demo.personas.<id>.label` enzovoort. */
    @WithParentName
    fun personas(): Map<String, PersonaInstelling>

    interface PersonaInstelling {

        /** Wat de keuzelijst toont. Dient tevens als aanhef in gegenereerde berichten. */
        fun label(): String

        /** BSN, RSIN of KVK. De keten kent daarnaast OIN, maar dat identificeert een afzender-organisatie, geen ontvanger. */
        fun type(): String

        fun waarde(): String

        /** Hoe een berichtenbox de inhoud presenteert; zie [PersonaBron]. */
        fun bron(): String

        /** De organisatie-OIN's waarvan deze persona berichten ontvangt. */
        fun magazijnen(): Optional<List<String>>
    }
}
