package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands.ReactiveRedisSubscriber
import io.smallrye.mutiny.Uni
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.function.Consumer

/**
 * Twee abonneerpogingen na elkaar, met een Redis-client die we zelf laten antwoorden. Het venster
 * waar het om gaat — de subscriber van een opgegeven poging die pas binnenkomt als de volgende al
 * loopt — is tegen een echte Redis niet op commando te openen.
 */
class AanmeldingenPogingenTest {

    private val pubsub = mockk<ReactivePubSubCommands<String>>()

    private val redis = mockk<ReactiveRedisDataSource> { every { pubsub(String::class.java) } returns pubsub }

    private val pod = RedisAanmeldingen(redis, Duration.ofMinutes(1))

    private val eersteSubscriber = subscriber()

    private val tweedeSubscriber = subscriber()

    // Welke subscriber elke volgende `subscribe` oplevert; de eerste pas als de test dat zegt.
    private val eersteOplevering = CompletableFuture<ReactiveRedisSubscriber>()

    private val opleveringen = ArrayDeque(listOf(Uni.createFrom().completionStage(eersteOplevering), Uni.createFrom().item(tweedeSubscriber)))

    @Volatile
    private var publicerenLukt = false

    init {
        every { pubsub.subscribe(any<String>(), any<Consumer<String>>(), any<Runnable>(), any<Consumer<Throwable>>()) } answers {
            opleveringen.removeFirst()
        }

        // Een geslaagde PUBLISH komt, zoals op een echt kanaal, bij de pod zelf terug.
        every { pubsub.publish(RedisAanmeldingen.KANAAL, any()) } answers {
            if (publicerenLukt) {
                pod.verdeel(secondArg())
                Uni.createFrom().voidItem()
            } else {
                Uni.createFrom().failure(IllegalStateException("PUBLISH geweigerd"))
            }
        }
    }

    @Test
    fun `de late subscriber van een opgegeven poging raakt de volgende poging niet`() {
        val ontvangen = LinkedBlockingQueue<UUID>()
        pod.registreer("sessie", { ontvangen += it }, {}, {})

        // De eerste poging geeft op: de probe gaat niet over het kanaal, en de subscriber is er nog niet.
        assertThrows<RuntimeException> { pod.actief().await().atMost(WACHTTIJD) }

        publicerenLukt = true
        pod.actief().await().atMost(WACHTTIJD)
        eersteOplevering.complete(eersteSubscriber)

        verify(exactly = 1) { eersteSubscriber.unsubscribe() }
        verify(exactly = 0) { tweedeSubscriber.unsubscribe() }

        val berichtId = UUID.randomUUID()
        pod.meld("sessie", berichtId).await().atMost(WACHTTIJD)

        assertEquals(berichtId, ontvangen.poll())

        // Bij het stoppen hoort de pod de tweede nog te kennen, anders blijft die connection open.
        pod.stop()

        verify(exactly = 1) { tweedeSubscriber.unsubscribe() }
    }

    private fun subscriber() = mockk<ReactiveRedisSubscriber> { every { unsubscribe() } returns Uni.createFrom().voidItem() }

    private companion object {
        val WACHTTIJD: Duration = Duration.ofSeconds(5)
    }
}
