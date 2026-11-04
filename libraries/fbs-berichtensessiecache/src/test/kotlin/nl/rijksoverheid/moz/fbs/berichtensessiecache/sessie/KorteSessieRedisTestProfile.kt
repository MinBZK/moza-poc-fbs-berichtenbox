package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.test.junit.QuarkusTestProfile
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockMagazijnClientFactory

/**
 * Echte Redis met sessieduren van enkele seconden, zodat verlopen door inactiviteit en het plafond
 * van de maximale duur binnen een test te zien zijn. De maximale duur ligt net boven de
 * inactiviteit: alleen dan kan een verlenging door activiteit tegen het plafond aanlopen. Seconden
 * en geen fracties daarvan: een test die bewijst dat een sessie nog loopt, heeft dan minstens een
 * seconde speling op een trage runner.
 */
class KorteSessieRedisTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        MockMagazijnClientFactory::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "true",
        "quarkus.redis.devservices.image-name" to "redis/redis-stack-server:7.4.0-v3",
        "berichtensessiecache.sessie.inactiviteit" to "PT4S",
        "berichtensessiecache.sessie.max-duur" to "PT6S",
    )
}
