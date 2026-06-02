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
    )
}
