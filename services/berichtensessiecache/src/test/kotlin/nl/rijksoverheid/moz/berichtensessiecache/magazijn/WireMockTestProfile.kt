package nl.rijksoverheid.moz.berichtensessiecache.magazijn

import io.quarkus.test.junit.QuarkusTestProfile
import nl.rijksoverheid.moz.berichtensessiecache.berichten.MockBerichtenCache

class WireMockTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        MockBerichtenCache::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "false",
        "quarkus.redis.hosts" to "redis://localhost:6379",
    )
}
