package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.hash.ReactiveHashCommands
import io.smallrye.mutiny.Uni
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * `renewBerichtTtls` is bewust best-effort (`onFailure().recoverWithNull()`): een mislukte
 * TTL-verlenging mag een al geslaagde read nooit alsnog laten falen, anders zou een transiënte
 * Redis-storing op de sliding-TTL-batch een werkende `getById` in een 500 veranderen. Deze test
 * dwingt die mislukking af door de onderliggende transactie te laten falen, zonder de read-kant
 * aan te raken — mocked op het niveau van `ReactiveRedisDataSource` omdat een echte Redis-fout op
 * enkel de TTL-batch niet reproduceerbaar is zonder de read-transactie mee te raken.
 */
class RedisBerichtenCacheRenewFailureTest {

    private val redis = mockk<ReactiveRedisDataSource>(relaxed = false)

    @Test
    fun `mislukte TTL-verlenging laat een geslaagde getById onaangetast`() {
        val berichtId = UUID.randomUUID()
        val ontvanger = Oin("00000001234567890000")
        val berichtKey = BerichtenCache.berichtKey(berichtId)

        val hashCommands = mockk<ReactiveHashCommands<String, String, String>>()
        every { redis.hash(String::class.java) } returns hashCommands
        every { hashCommands.hgetall(berichtKey) } returns Uni.createFrom().item(
            mapOf(
                "berichtId" to berichtId.toString(),
                "afzender" to "00000001800866472000",
                "afzenderNaam" to "Testmagazijn",
                "ontvanger" to ontvanger.waarde,
                "ontvangerType" to "OIN",
                "onderwerp" to "onderwerp",
                "inhoud" to "inhoud",
                "publicatietijdstip" to "2026-01-01T00:00:00Z",
                "magazijnId" to "00000001800866472000",
                "aantalBijlagen" to "0",
            ),
        )
        // renewBerichtTtls draait in een eigen withTransaction — laat die falen zonder de
        // read-transactie (hgetall hierboven) te raken.
        every { redis.withTransaction(any()) } returns
            Uni.createFrom().failure(RuntimeException("Redis-verbinding weg tijdens TTL-batch"))

        val cache = RedisBerichtenCache(
            redis = redis,
            objectMapper = ObjectMapper(),
            ttl = Duration.ofHours(12),
            aggregationLockTtl = Duration.ofMinutes(2),
            startupRedisearchTimeoutSeconds = 5,
            redisBatchgrootte = 256,
            maxWaitingHandlers = 2048,
        )

        val bericht = cache.getById(berichtId, ontvanger).await().indefinitely()

        assertNotNull(bericht, "read moet slagen ondanks een mislukte TTL-verlenging")
        assertEquals(berichtId, bericht!!.berichtId)
    }
}
