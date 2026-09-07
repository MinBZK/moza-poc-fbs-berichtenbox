package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.databind.ObjectMapper
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
 * Pin de ondergrens op `berichtensessiecache.startup-redisearch-timeout-seconds` en op
 * `berichtensessiecache.redis-batchgrootte`: een 0/negatieve waarde maakt `atMost(ZERO)`
 * onbegrensd, resp. laat `chunked` pas bij de eerste store falen. Beide guards staan bewust
 * vóór de Redis-calls in `init()`, zodat ze ook zonder bereikbare Redis aanslaan — daarom raken
 * deze tests de (niet-gestubde) Redis-mock niet.
 */
class RedisBerichtenCacheInitTest {

    private val redis = mockk<ReactiveRedisDataSource>(relaxed = false)

    private fun cache(startupTimeoutSeconds: Long = 5L, redisBatchgrootte: Int = 256) = RedisBerichtenCache(
        redis = redis,
        objectMapper = ObjectMapper(),
        ttl = Duration.ofHours(12),
        aggregationLockTtl = Duration.ofMinutes(2),
        startupRedisearchTimeoutSeconds = startupTimeoutSeconds,
        redisBatchgrootte = redisBatchgrootte,
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
}
