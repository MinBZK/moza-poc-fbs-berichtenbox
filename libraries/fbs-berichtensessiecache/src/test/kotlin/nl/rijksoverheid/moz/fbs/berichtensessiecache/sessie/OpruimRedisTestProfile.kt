package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.test.junit.QuarkusTestProfile
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockMagazijnClientFactory
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockMagazijnResolver

/**
 * Echte Redis met sessies die een test overleven, en een vangnetmarge van één seconde. Zo is binnen
 * een test te zien dat gegevens zonder optrekken vóór de vangnet-TTL van een latere sessie zouden
 * verlopen: wacht anderhalve seconde en de TTL ligt onder de maximale duur.
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
        "berichtensessiecache.sessie.vangnet-marge" to "PT1S",
        "berichtensessiecache.aggregation-lock-ttl" to "PT5S",
    )
}
