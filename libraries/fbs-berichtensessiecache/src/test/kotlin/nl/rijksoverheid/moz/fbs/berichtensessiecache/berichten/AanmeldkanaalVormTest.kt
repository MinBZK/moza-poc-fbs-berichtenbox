package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands
import io.smallrye.mutiny.Uni
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * De vorm van een bericht op het aanmeldkanaal. Een nieuw bericht houdt de vorm van de vorige
 * versie: tijdens een rolling update lezen pods op die versie het kanaal nog, en zij kennen geen
 * soort. Een wijziging of verwijdering kunnen zij toch niet doorgeven.
 */
class AanmeldkanaalVormTest {

    private val gepubliceerd = CopyOnWriteArrayList<String>()

    private val pubsub = mockk<ReactivePubSubCommands<String>> {
        every { publish(RedisAanmeldingen.KANAAL, any()) } answers {
            gepubliceerd += secondArg<String>()
            Uni.createFrom().voidItem()
        }
    }

    private val pod = RedisAanmeldingen(mockk<ReactiveRedisDataSource> { every { pubsub(String::class.java) } returns pubsub }, Duration.ofMinutes(1))

    @ParameterizedTest(name = "{0}")
    @CsvSource("BIJGEKOMEN,''", "GEWIJZIGD,' gewijzigd'", "VERWIJDERD,' verwijderd'")
    internal fun `elke soort heeft één vaste vorm op het kanaal`(wijziging: Berichtwijziging, achtervoegsel: String) {
        val sleutel = BerichtSleutel(MAGAZIJN, UUID.fromString("0b0e5a52-8a8f-4d5f-9d2e-2f7f7a0c1d11"))

        pod.meld(CACHE_KEY, sleutel, wijziging).await().atMost(Duration.ofSeconds(5))

        assertEquals(listOf("$CACHE_KEY ${sleutel.berichtId} $MAGAZIJN$achtervoegsel"), gepubliceerd)
    }

    private companion object {
        const val MAGAZIJN = "00000001003214345000"
        const val CACHE_KEY = "berichtensessiecache:v4:abc"
    }
}
