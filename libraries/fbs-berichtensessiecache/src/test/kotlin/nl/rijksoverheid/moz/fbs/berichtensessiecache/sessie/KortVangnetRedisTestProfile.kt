package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.test.junit.QuarkusTestProfile
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockMagazijnClientFactory

/**
 * Echte Redis met een vangnet-TTL van drie seconden (maximale duur 2 s plus marge 1 s), zodat een
 * test kan zien dat gegevens zonder actief opruimen echt verlopen.
 */
class KortVangnetRedisTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        MockMagazijnClientFactory::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "true",
        "quarkus.redis.devservices.image-name" to "redis/redis-stack-server:7.4.0-v3",
        "berichtensessiecache.sessie.inactiviteit" to "PT1S",
        "berichtensessiecache.sessie.max-duur" to "PT2S",
        "berichtensessiecache.sessie.vangnet-marge" to "PT1S",
    )
}
