package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * Pin de ondergrens op `berichtensessiecache.startup-redisearch-timeout-seconds`: een
 * 0/negatieve waarde maakt `atMost(ZERO)` onbegrensd en zou de startup-bescherming stil
 * uitschakelen. De guard staat bewust vóór de Redis-calls in `init()`, zodat hij ook zonder
 * bereikbare Redis aanslaat — daarom raakt deze test de (niet-gestubde) Redis-mock niet.
 */
class RedisBerichtenCacheInitTest {

    private val redis = mockk<ReactiveRedisDataSource>(relaxed = false)

    private fun cache(startupTimeoutSeconds: Long) = RedisBerichtenCache(
        redis = redis,
        objectMapper = ObjectMapper(),
        ttl = Duration.ofHours(12),
        aggregationLockTtl = Duration.ofMinutes(2),
        startupRedisearchTimeoutSeconds = startupTimeoutSeconds,
    )

    @Test
    fun `startup-redisearch-timeout van 0 wordt geweigerd vóór Redis geraakt wordt`() {
        val ex = assertThrows<IllegalArgumentException> { cache(0).init() }

        assertTrue(ex.message!!.contains("startup-redisearch-timeout-seconds"), "Was: ${ex.message}")
        verify(exactly = 0) { redis.search() }
    }

    @Test
    fun `negatieve startup-redisearch-timeout wordt geweigerd`() {
        val ex = assertThrows<IllegalArgumentException> { cache(-1).init() }

        assertTrue(ex.message!!.contains("groter zijn dan 0"), "Was: ${ex.message}")
    }
}
