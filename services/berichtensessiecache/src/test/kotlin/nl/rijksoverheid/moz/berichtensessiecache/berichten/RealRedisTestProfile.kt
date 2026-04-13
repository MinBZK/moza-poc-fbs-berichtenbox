package nl.rijksoverheid.moz.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTestProfile

class RealRedisTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        MockMagazijnClientFactory::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "berichtensessiecache.ttl" to "PT2S",
    )
}
