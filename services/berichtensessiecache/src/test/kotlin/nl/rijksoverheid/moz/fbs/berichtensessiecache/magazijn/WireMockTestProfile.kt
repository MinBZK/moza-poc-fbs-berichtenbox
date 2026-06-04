package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.quarkus.test.junit.QuarkusTestProfile
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockBerichtenCache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockMagazijnResolver

class WireMockTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        MockBerichtenCache::class.java,
        MockMagazijnResolver::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "false",
        "quarkus.redis.hosts" to "redis://localhost:6379",
        // Niet-default timeouts zodat de tests bewijzen dat de geconfigureerde waarden
        // gehonoreerd worden (niet de prod-defaults 10s/12000ms) én de suite snel blijft.
        // Invariant read-timeout (4000ms) > query-timeout (2s × 1000) blijft gerespecteerd.
        "berichtensessiecache.magazijn-query-timeout-seconds" to "2",
        "magazijn-client.read-timeout-ms" to "4000",
    )
}
