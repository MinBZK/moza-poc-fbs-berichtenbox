package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.hash.ReactiveHashCommands
import io.smallrye.mutiny.Uni
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.TestSessieConfig
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * Lezen raakt alleen wat het leest. Vroeger verlengde elke read de TTL van de gegevens; nu is die
 * TTL een vast vangnet ná de laatste sessie, en een read die toch nog een transactie of
 * TTL-commando stuurt, zou dat vangnet weer laten meeschuiven. De mock zonder `relaxed` laat elke
 * onverwachte aanroep falen.
 */
class RedisBerichtenCacheLezenTest {

    private val redis = mockk<ReactiveRedisDataSource>(relaxed = false)

    @Test
    fun `getById leest alleen de hash en stuurt geen TTL-commando`() {
        val berichtId = UUID.randomUUID()
        val ontvanger = Oin("00000001234567890000")
        val berichtKey = BerichtenCache.berichtKey(BerichtSleutel("00000001800866472000", berichtId))

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
                "publicatietijdstip" to "2026-01-01T00:00:00Z",
                "magazijnId" to "00000001800866472000",
                "aantalBijlagen" to "0",
            ),
        )

        val cache = RedisBerichtenCache(
            redis = redis,
            objectMapper = ObjectMapper(),
            sessieConfig = TestSessieConfig(),
            aggregationLockTtl = Duration.ofMinutes(2),
            startupRedisearchTimeoutSeconds = 5,
            redisBatchgrootte = 256,
            maxWaitingHandlers = 2048,
        )

        val bericht = cache.getById(BerichtSleutel("00000001800866472000", berichtId), ontvanger).await().indefinitely()

        assertNotNull(bericht)
        assertEquals(berichtId, bericht!!.berichtId)
        verify(exactly = 0) { redis.key() }
        verify(exactly = 0) { redis.withTransaction(any()) }
    }
}
