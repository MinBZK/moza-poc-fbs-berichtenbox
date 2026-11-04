package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTestProfile

class RealRedisTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        MockMagazijnClientFactory::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "true",
        "quarkus.redis.devservices.image-name" to "redis/redis-stack-server:7.4.0-v3",
        // Een TTL op de gegevens van 2 s (maximale duur plus marge), zodat verlopen binnen een test
        // te zien is. Sessies spelen in dit profiel geen rol.
        "berichtensessiecache.sessie.max-duur" to "PT1S",
        "berichtensessiecache.sessie.vangnet-marge" to "PT1S",
        "berichtensessiecache.aggregation-lock-ttl" to "PT2S",
    )
}
