package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integratietest van [RedisAanmeldDeduplicatie] tegen een echte Redis (Dev
 * Services). Bewaakt de NX-semantiek, rollback via [AanmeldDeduplicatie.verwijder]
 * en dat de marker een TTL krijgt (geen onbeperkt groeiende keyspace).
 *
 * De sessiecache-facade is gemockt zodat de test geen magazijn-backends nodig
 * heeft; alleen het deduplicatie-pad raakt Redis.
 */
@QuarkusTest
@TestProfile(RedisDedupProfile::class)
class RedisAanmeldDeduplicatieIntegrationTest {

    @Inject
    lateinit var dedup: RedisAanmeldDeduplicatie

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    private fun nieuwId(): String = "evt-${UUID.randomUUID()}"

    @Test
    fun `eerste keer true, daarna duplicaat false`() {
        val id = nieuwId()

        assertTrue(dedup.eerstgezien(id))
        assertFalse(dedup.eerstgezien(id))
    }

    @Test
    fun `verwijder maakt herverwerken mogelijk`() {
        val id = nieuwId()

        assertTrue(dedup.eerstgezien(id))
        dedup.verwijder(id)
        assertTrue(dedup.eerstgezien(id))
    }

    @Test
    fun `marker krijgt een positieve TTL`() {
        val id = nieuwId()
        dedup.eerstgezien(id)

        val ttl = redis.key(String::class.java).ttl("aanmeld:event:$id").await().indefinitely()

        assertTrue(ttl > 0, "verwachtte positieve TTL, was $ttl")
    }

    @Test
    fun `gelijktijdige afleveringen van hetzelfde event zien er hooguit een als eerste`() {
        val id = nieuwId()
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val eersten = AtomicInteger(0)

        try {
            val taken = (1..threads).map {
                pool.submit { if (dedup.eerstgezien(id)) eersten.incrementAndGet() }
            }

            taken.forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(1, eersten.get(), "NX moet precies één winnaar opleveren bij concurrent claims")
    }
}

/**
 * Echte Redis voor het deduplicatie-pad; de facade blijft gemockt en de
 * library-beans blijven unremovable (zoals in de overige uitvraag-tests) zodat de
 * config-roots resolven. Géén `quarkus.redis.hosts`: dat zou Dev Services
 * onderdrukken.
 */
class RedisDedupProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(MockSessiecache::class.java)

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.arc.unremovable-types" to "nl.rijksoverheid.moz.fbs.berichtensessiecache.**",
        "quarkus.redis.devservices.enabled" to "true",
        "quarkus.redis.devservices.image-name" to "redis/redis-stack-server:7.4.0-v3",
    )
}
