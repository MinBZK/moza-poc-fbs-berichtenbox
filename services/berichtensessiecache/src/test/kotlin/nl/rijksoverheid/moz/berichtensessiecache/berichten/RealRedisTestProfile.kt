package nl.rijksoverheid.moz.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTestProfile

class RealRedisTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        MockMagazijnClientFactory::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "false",
        "quarkus.redis.hosts" to "redis://localhost:6379",
        "berichtensessiecache.ttl" to "PT2S",
    )
}
