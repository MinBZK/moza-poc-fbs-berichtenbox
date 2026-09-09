package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Reproduceert de productie-storing op testschaal: `max-waiting-handlers` staat hier op 64 in
 * plaats van de Vert.x-default 2048, zodat een handvol honderden berichten al genoeg is om de
 * wachtrij te laten vollopen wanneer alle commando's tegelijk aangeboden worden. De batchgrootte
 * staat er ruim onder, zodat de test de begrenzing meet en niet een toevallig gehaalde marge.
 *
 * De TTL's staan bewust ruim (in tegenstelling tot RealRedisTestProfile): deze test schrijft
 * honderden berichten en controleert daarna de sleutels, wat langer duurt dan een TTL van 2s.
 */
class KleineBatchRedisTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        MockMagazijnClientFactory::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "true",
        "quarkus.redis.devservices.image-name" to "redis/redis-stack-server:7.4.0-v3",
        "quarkus.redis.max-waiting-handlers" to "64",
        "berichtensessiecache.redis-batchgrootte" to "16",
        "berichtensessiecache.ttl" to "PT5M",
        "berichtensessiecache.aggregation-lock-ttl" to "PT5M",
    )
}
