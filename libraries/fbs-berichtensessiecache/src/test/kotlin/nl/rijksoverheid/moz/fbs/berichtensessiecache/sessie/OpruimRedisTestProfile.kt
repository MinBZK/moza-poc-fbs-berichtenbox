package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.test.junit.QuarkusTestProfile
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockMagazijnClientFactory
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockMagazijnResolver

/**
 * Echte Redis met sessies die een test overleven. Twintig seconden is lang genoeg voor een test en
 * kort genoeg om binnen een test te zien of een TTL opnieuw gezet is: na anderhalve seconde wachten
 * ligt een niet opgetrokken TTL een seconde onder de maximale duur.
 */
class OpruimRedisTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        MockMagazijnClientFactory::class.java,
        MockMagazijnResolver::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "true",
        "quarkus.redis.devservices.image-name" to "redis/redis-stack-server:7.4.0-v3",
        "berichtensessiecache.sessie.max-duur" to "PT20S",
        "berichtensessiecache.aggregation-lock-ttl" to "PT5S",
    )
}
