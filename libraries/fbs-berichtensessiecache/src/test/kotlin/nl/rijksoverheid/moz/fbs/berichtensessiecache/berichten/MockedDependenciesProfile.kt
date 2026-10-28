package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTestProfile
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.MockSessieRegister

class MockedDependenciesProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        MockBerichtenCache::class.java,
        MockMagazijnClientFactory::class.java,
        MockMagazijnResolver::class.java,
        MockAanmeldingen::class.java,
        MockSessieRegister::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "false",
        "quarkus.redis.hosts" to "redis://localhost:6379",
    )
}
