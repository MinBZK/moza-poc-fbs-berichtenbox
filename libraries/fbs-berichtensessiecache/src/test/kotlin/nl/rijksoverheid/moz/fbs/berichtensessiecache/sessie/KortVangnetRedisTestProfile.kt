package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.test.junit.QuarkusTestProfile
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.MockMagazijnClientFactory

/**
 * Echte Redis met een vangnet-TTL van vijf seconden (maximale duur 4 s plus marge 1 s), zodat een
 * test kan zien dat gegevens zonder actief opruimen echt verlopen. De verloopindex leeft een maximale
 * duur langer dan gegevens die direct bij de start van de sessie geschreven zijn; van dat venster gaat
 * af wat de test vóór het wachten nodig heeft. Met een maximale duur van 4 s blijft daarvan genoeg
 * over, ook op een drukke runner met parallelle forks. De grens per ontvanger staat op 2, zodat de
 * configuratie aantoonbaar doorwerkt en niet alleen de standaardwaarde 5 getoetst is.
 */
class KortVangnetRedisTestProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        MockMagazijnClientFactory::class.java,
    )

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.redis.devservices.enabled" to "true",
        "quarkus.redis.devservices.image-name" to "redis/redis-stack-server:7.4.0-v3",
        "berichtensessiecache.sessie.inactiviteit" to "PT1S",
        "berichtensessiecache.sessie.max-duur" to "PT4S",
        "berichtensessiecache.sessie.vangnet-marge" to "PT1S",
        "berichtensessiecache.sessie.max-per-ontvanger" to "2",
    )
}
