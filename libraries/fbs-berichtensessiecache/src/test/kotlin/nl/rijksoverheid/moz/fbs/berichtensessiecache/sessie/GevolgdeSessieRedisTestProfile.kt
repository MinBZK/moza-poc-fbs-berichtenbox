package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.test.junit.QuarkusTestProfile
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockMagazijnClientFactory

/**
 * Echte Redis met een inactiviteit van 2 s en een hartslag van 0,5 s, zodat binnen een test te zien
 * is dat een gevolgde sessie toch door inactiviteit verloopt, en dat de stream dat binnen een hartslag
 * merkt. De maximale duur ligt ruim boven de looptijd van een test: het plafond mag hier niet de
 * oorzaak van een verloop zijn.
 */
class GevolgdeSessieRedisTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        MockMagazijnClientFactory::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "true",
        "quarkus.redis.devservices.image-name" to "redis/redis-stack-server:7.4.0-v3",
        "berichtensessiecache.sessie.inactiviteit" to "PT2S",
        "berichtensessiecache.sessie.max-duur" to "PT1M",
        "berichtensessiecache.volg-hartslag" to "PT0.5S",
    )
}
