package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber
import io.smallrye.mutiny.subscription.UniEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * De batcher bestaat om het aantal in-flight Redis-commando's los te koppelen van de lengte van de
 * lijst. Deze tests meten daarom niet of het resultaat klopt (dat doet de integratietest), maar of
 * de piek aan gelijktijdig aangeboden commando's daadwerkelijk begrensd blijft.
 */
// @QuarkusTest zodat deze MockK-loze unit-coverage in jacoco-quarkus.exec terechtkomt
// (quarkus-jacoco telt alleen @QuarkusTest-paden mee).
@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
class RedisBatchingTest {

    /**
     * Registreert per aangeboden item een emitter die pas op commando afrondt. Zo staat het
     * aantal openstaande emitters gelijk aan het aantal in-flight commando's en is de piek
     * meetbaar zonder timing-afhankelijkheid.
     */
    private class BatchSpion {
        private val openstaand = mutableListOf<UniEmitter<in Void>>()
        val aangeboden = mutableListOf<Int>()
        var piek = 0
            private set

        fun commando(item: Int): Uni<Void> = Uni.createFrom().emitter { emitter ->
            aangeboden.add(item)
            openstaand.add(emitter)

            if (openstaand.size > piek) piek = openstaand.size
        }

        /** Rondt precies de nu-openstaande commando's af; dat laat de volgende batch los. */
        fun rondHuidigeBatchAf() {
            val huidige = openstaand.toList()

            openstaand.clear()
            huidige.forEach { it.complete(null) }
        }

        /** Laat het eerste openstaande commando falen; de rest blijft hangen. */
        fun laatEersteFalen(fout: Throwable) {
            val eerste = openstaand.first()

            openstaand.clear()
            eerste.fail(fout)
        }

        fun openstaand(): Int = openstaand.size
    }

    @Test
    fun `piek aan in-flight commando's blijft op de batchgrootte, niet op de lijstlengte`() {
        val spion = BatchSpion()
        val subscriber = RedisBatching.inBatches((1..100).toList(), batchgrootte = 4) { spion.commando(it) }
            .subscribe().withSubscriber(UniAssertSubscriber.create<Void>())

        assertEquals(4, spion.openstaand(), "alleen de eerste batch mag aangeboden zijn")

        repeat(25) { spion.rondHuidigeBatchAf() }

        subscriber.assertCompleted()
        assertEquals(4, spion.piek, "piek moet de batchgrootte zijn, niet de lijstlengte")
        assertEquals(100, spion.aangeboden.size)
    }

    @Test
    fun `volgorde van aanbieden volgt de lijstvolgorde`() {
        val spion = BatchSpion()
        val subscriber = RedisBatching.inBatches((1..10).toList(), batchgrootte = 3) { spion.commando(it) }
            .subscribe().withSubscriber(UniAssertSubscriber.create<Void>())

        repeat(4) { spion.rondHuidigeBatchAf() }

        subscriber.assertCompleted()
        assertEquals((1..10).toList(), spion.aangeboden)
    }

    @ParameterizedTest(name = "{0} items bij batchgrootte {1} levert {2} batches")
    @CsvSource("0, 4, 0", "1, 4, 1", "4, 4, 1", "5, 4, 2", "9, 4, 3", "10, 1, 10")
    fun `elke cardinaliteit rondt af en biedt elk item precies eenmaal aan`(
        aantal: Int,
        batchgrootte: Int,
        verwachteBatches: Int,
    ) {
        val spion = BatchSpion()
        val subscriber = RedisBatching.inBatches((1..aantal).toList(), batchgrootte) { spion.commando(it) }
            .subscribe().withSubscriber(UniAssertSubscriber.create<Void>())

        repeat(verwachteBatches) { spion.rondHuidigeBatchAf() }

        subscriber.assertCompleted()
        assertEquals((1..aantal).toList(), spion.aangeboden)
        assertTrue(spion.piek <= batchgrootte, "piek ${spion.piek} overschreed batchgrootte $batchgrootte")
    }

    @Test
    fun `een fout in een batch propageert en stopt het aanbieden van volgende batches`() {
        val spion = BatchSpion()
        val fout = RuntimeException("Redis waiting queue is full")
        val subscriber = RedisBatching.inBatches((1..20).toList(), batchgrootte = 5) { spion.commando(it) }
            .subscribe().withSubscriber(UniAssertSubscriber.create<Void>())

        spion.rondHuidigeBatchAf()
        spion.laatEersteFalen(fout)

        subscriber.assertFailedWith(RuntimeException::class.java, "Redis waiting queue is full")
        assertEquals(10, spion.aangeboden.size, "batch 3 en verder mogen niet meer aangeboden zijn")
    }

    @ParameterizedTest
    @CsvSource("0", "-1")
    fun `een batchgrootte van 0 of lager wordt geweigerd`(batchgrootte: Int) {
        val ex = assertThrows<IllegalArgumentException> {
            RedisBatching.inBatches(listOf(1, 2, 3), batchgrootte) { Uni.createFrom().voidItem() }
        }

        assertTrue(ex.message!!.contains("batchgrootte"), "Was: ${ex.message}")
    }
}
