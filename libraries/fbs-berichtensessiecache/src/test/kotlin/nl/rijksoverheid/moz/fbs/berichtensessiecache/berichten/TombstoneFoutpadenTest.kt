package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.hash.ReactiveHashCommands
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands
import io.quarkus.redis.datasource.list.ReactiveListCommands
import io.quarkus.redis.datasource.value.ReactiveValueCommands
import io.smallrye.mutiny.Uni
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * De twee foutpaden van de tombstone, elk met een storing op precies één Redis-commando — iets
 * wat de integratietests tegen een echte Redis niet kunnen uitlokken.
 *
 * Beide gaan over hetzelfde: de tombstone is een verrijking van een misser, geen voorwaarde
 * ervoor. Faalt hij, dan hoort de gebruiker daar niets van te merken behalve dat het antwoord
 * terugvalt op "onbekend" — en niet dat een geslaagde verwijdering alsnog als fout eindigt of dat
 * een doodgewone 404 een 503 wordt.
 */
class TombstoneFoutpadenTest {

    private val redis = mockk<ReactiveRedisDataSource>()
    private val hash = mockk<ReactiveHashCommands<String, String, String>>()
    private val list = mockk<ReactiveListCommands<String, String>>()
    private val keys = mockk<ReactiveKeyCommands<String>>()
    private val values = mockk<ReactiveValueCommands<String, String>>()

    private val ontvanger = Oin("00000001003214345000")
    private val berichtId: UUID = UUID.randomUUID()

    private val cache = RedisBerichtenCache(
        redis = redis,
        objectMapper = ObjectMapper(),
        ttl = Duration.ofHours(12),
        aggregationLockTtl = Duration.ofMinutes(2),
        startupRedisearchTimeoutSeconds = 5,
    )

    private fun stubRedisCommands() {
        every { redis.hash(String::class.java) } returns hash
        every { redis.list(String::class.java) } returns list
        every { redis.key() } returns keys
        every { redis.value(String::class.java) } returns values
    }

    /** Een hash die de eigenaar-check haalt, zodat `delete` doorloopt tot de tombstone-schrijf. */
    private fun stubEigenBericht() {
        every { hash.hgetall(any()) } returns Uni.createFrom().item(
            mapOf(
                "ontvanger" to ontvanger.waarde,
                "ontvangerType" to ontvanger.type.name,
            ),
        )
        every { list.lrange(any(), 0, -1) } returns Uni.createFrom().item(emptyList())
        every { keys.del(any()) } returns Uni.createFrom().item(1)
    }

    @Test
    fun `een mislukte tombstone-schrijf laat de geslaagde verwijdering staan`() {
        // De hash is op dit punt al onherroepelijk verwijderd. De delete alsnog laten omvallen
        // levert een 502 op een verwijdering die wél doorging, plus een compensatie-invalidate die
        // niets meer te invalideren heeft.
        stubRedisCommands()
        stubEigenBericht()
        every { values.set(any(), any(), any()) } returns
            Uni.createFrom().failure(RuntimeException("OOM command not allowed"))

        // Faalt de Uni, dan gooit `await` hier — dát is de assertie.
        cache.delete(berichtId, ontvanger).await().atMost(Duration.ofSeconds(5))
    }

    @Test
    fun `een mislukte tombstone-lookup levert geen storing maar een gewone misser`() {
        // Zonder deze terugval wordt een doodgewone 404 een 503 met `Retry-After`, en gaat de
        // client wachten op een bericht dat nooit bestond.
        stubRedisCommands()
        every { keys.exists(any<String>()) } returns
            Uni.createFrom().failure(RuntimeException("connection reset"))

        val verwijderd = cache.isVerwijderdVoor(berichtId, ontvanger).await().atMost(Duration.ofSeconds(5))

        assertFalse(verwijderd)
    }
}
