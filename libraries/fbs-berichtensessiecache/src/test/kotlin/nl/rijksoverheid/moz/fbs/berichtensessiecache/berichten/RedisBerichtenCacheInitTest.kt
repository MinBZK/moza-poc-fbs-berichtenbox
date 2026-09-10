package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

/**
 * Pin de ondergrens op `berichtensessiecache.startup-redisearch-timeout-seconds`, op
 * `berichtensessiecache.redis-batchgrootte` en de invariant tussen die batchgrootte en
 * `quarkus.redis.max-waiting-handlers`: een 0/negatieve timeout maakt `atMost(ZERO)` onbegrensd,
 * een 0/negatieve batchgrootte laat `chunked` pas bij de eerste store falen, en een batchgrootte
 * die de wachtrij-grens haalt of overschrijdt laat een store pas in de laatste stap van een
 * ophaalronde omvallen. Alle drie de guards staan bewust vóór de Redis-calls in `init()`, zodat
 * ze ook zonder bereikbare Redis aanslaan — daarom raken de weiger-tests de (niet-gestubde)
 * Redis-mock niet.
 */
class RedisBerichtenCacheInitTest {

    private val redis = mockk<ReactiveRedisDataSource>(relaxed = false)

    private fun cache(
        startupTimeoutSeconds: Long = 5L,
        redisBatchgrootte: Int = 256,
        maxWaitingHandlers: Int = 2048,
    ) = RedisBerichtenCache(
        redis = redis,
        objectMapper = ObjectMapper(),
        ttl = Duration.ofHours(12),
        aggregationLockTtl = Duration.ofMinutes(2),
        startupRedisearchTimeoutSeconds = startupTimeoutSeconds,
        redisBatchgrootte = redisBatchgrootte,
        maxWaitingHandlers = maxWaitingHandlers,
    )

    @Test
    fun `startup-redisearch-timeout van 0 wordt geweigerd vóór Redis geraakt wordt`() {
        val ex = assertThrows<IllegalArgumentException> { cache(startupTimeoutSeconds = 0).init() }

        assertTrue(ex.message!!.contains("startup-redisearch-timeout-seconds"), "Was: ${ex.message}")
        verify(exactly = 0) { redis.search() }
    }

    @Test
    fun `negatieve startup-redisearch-timeout wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> { cache(startupTimeoutSeconds = -1).init() }

        assertTrue(ex.message!!.contains("groter zijn dan 0"), "Was: ${ex.message}")
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `redis-batchgrootte van 0 of lager wordt geweigerd vóór Redis geraakt wordt`(batchgrootte: Int) {
        // Zonder ondergrens zou een 0 pas bij de eerste store falen, midden in een ophaalronde,
        // met een melding uit `chunked` die de configuratiesleutel niet noemt.
        val ex = assertThrows<IllegalArgumentException> { cache(redisBatchgrootte = batchgrootte).init() }

        assertTrue(ex.message!!.contains("redis-batchgrootte"), "Was: ${ex.message}")
        verify(exactly = 0) { redis.search() }
    }

    @ParameterizedTest
    @ValueSource(ints = [2048, 2049])
    fun `redis-batchgrootte gelijk aan of boven max-waiting-handlers wordt geweigerd vóór Redis geraakt wordt`(batchgrootte: Int) {
        // Gelijk aan de grens is óók een overschrijding: de piek per batch is de batchgrootte
        // zelf, dus een batchgrootte van precies max-waiting-handlers vult de wachtrij volledig.
        val ex = assertThrows<IllegalArgumentException> {
            cache(redisBatchgrootte = batchgrootte, maxWaitingHandlers = 2048).init()
        }

        assertTrue(ex.message!!.contains("redis-batchgrootte"), "Was: ${ex.message}")
        assertTrue(ex.message!!.contains("max-waiting-handlers"), "Was: ${ex.message}")
        assertTrue(ex.message!!.contains("2048"), "Was: ${ex.message}")
        verify(exactly = 0) { redis.search() }
    }

    @Test
    fun `redis-batchgrootte comfortabel onder max-waiting-handlers passeert de guard`() {
        // Forceer een herkenbare fout ná de guard, zodat een geslaagde `redis.search()`-aanroep
        // bewijst dat de invariant-check geen valse afwijzing gaf — zonder de rest van `init()`
        // (RediSearch-bootstrap) te hoeven stubben.
        every { redis.search() } throws RuntimeException("guard gepasseerd; vervolgpad bewust ongestubd")

        assertThrows<IllegalStateException> {
            cache(redisBatchgrootte = 256, maxWaitingHandlers = 2048).init()
        }

        verify(exactly = 1) { redis.search() }
    }
}
