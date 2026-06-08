package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands
import io.quarkus.redis.datasource.value.ReactiveValueCommands
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.TimeoutException
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * Unit-test voor de foutvertaling van [RedisAanmeldDeduplicatie]: een falende of
 * vastlopende Redis-operatie moet als 503 (transient) naar de caller, niet als 500.
 * De Redis-datasource is gemockt zodat dit zonder echte Redis testbaar is (de
 * happy-paden draaien tegen echte Redis in de integratietest).
 */
class RedisAanmeldDeduplicatieTest {

    private val values = mockk<ReactiveValueCommands<String, String>>()
    private val keys = mockk<ReactiveKeyCommands<String>>()
    private val redis = mockk<ReactiveRedisDataSource> {
        every { value(String::class.java) } returns values
        every { key(String::class.java) } returns keys
    }
    private val config = mockk<AanmeldConfig> {
        every { deduplicatie() } returns mockk {
            every { ttl() } returns Duration.ofHours(24)
            every { redisAwaitTimeout() } returns Duration.ofSeconds(2)
        }
    }
    private val dedup = RedisAanmeldDeduplicatie(redis, config)

    private fun configMet(ttl: Duration, awaitTimeout: Duration) = mockk<AanmeldConfig> {
        every { deduplicatie() } returns mockk {
            every { ttl() } returns ttl
            every { redisAwaitTimeout() } returns awaitTimeout
        }
    }

    @Test
    fun `redis-fout bij markeren wordt 503`() {
        every { values.setAndChanged(any(), any(), any<SetArgs>()) } returns
            Uni.createFrom().failure(RuntimeException("connection reset"))

        val ex = assertThrows<WebApplicationException> { dedup.eerstgezien("evt-1") }

        assertEquals(503, ex.response.status)
    }

    @Test
    fun `timeout bij markeren wordt 503`() {
        every { values.setAndChanged(any(), any(), any<SetArgs>()) } returns
            Uni.createFrom().failure(TimeoutException())

        val ex = assertThrows<WebApplicationException> { dedup.eerstgezien("evt-1") }

        assertEquals(503, ex.response.status)
    }

    @Test
    fun `geslaagde NX-write geeft true`() {
        every { values.setAndChanged(any(), any(), any<SetArgs>()) } returns Uni.createFrom().item(true)

        assertTrue(dedup.eerstgezien("evt-1"))
    }

    @Test
    fun `redis-await-timeout van nul wordt geweigerd bij constructie`() {
        val ex = assertThrows<IllegalArgumentException> {
            RedisAanmeldDeduplicatie(redis, configMet(ttl = Duration.ofHours(24), awaitTimeout = Duration.ZERO))
        }

        assertTrue(ex.message!!.contains("redis-await-timeout"), "Was: ${ex.message}")
    }
}
