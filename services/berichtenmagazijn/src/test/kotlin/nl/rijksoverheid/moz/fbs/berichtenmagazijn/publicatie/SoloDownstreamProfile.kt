package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Publicatie-tests die het schrijfpad los van de scheduler onderzoeken: één downstream naar een
 * poort waar niets luistert, zodat er wel deliveries gepland worden maar niets afgeleverd raakt.
 *
 * De downstream-URL mag hier wél uit een profiel komen, terwijl [DownstreamStubLifecycle]
 * uitlegt dat dat voor de stream-tests te laat aankomt: met de scheduler uit leest geen enkele
 * bean de downstream-map, dus de timing doet hier niet ter zake.
 *
 * Gedeeld door alle tests met deze behoefte. Elke testklasse met een eigen profielklasse dwingt
 * een aparte Quarkus-instantie plus een verse database-container af, ook als de configuratie
 * identiek is; drie kopieën van deze twee sleutels kostten drie starts waar één volstaat.
 */
class SoloDownstreamProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "magazijn.publicatie.downstreams.aanmeld.url" to "http://localhost:1/events",
        "quarkus.scheduler.enabled" to "false",
    )
}
