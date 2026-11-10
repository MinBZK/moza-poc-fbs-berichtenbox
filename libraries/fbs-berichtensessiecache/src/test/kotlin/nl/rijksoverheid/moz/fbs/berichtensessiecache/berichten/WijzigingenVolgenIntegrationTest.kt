package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.helpers.test.AssertSubscriber
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.GevolgdeSessieRedisTestProfile
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.SessieRegister
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Een wijziging in het ene tabblad bereikt de andere open berichtenboxen van dezelfde ontvanger,
 * ook op een andere pod, tegen een echte Redis. De bijwerking loopt via de facade van deze pod; de
 * tweede pod is een los gebouwde [RedisAanmeldingen] met zijn eigen [SessieVolger], precies zoals
 * een tweede replica.
 */
@QuarkusTest
@TestProfile(GevolgdeSessieRedisTestProfile::class)
class WijzigingenVolgenIntegrationTest {

    @Inject
    lateinit var sessiecache: Sessiecache

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    @Inject
    internal lateinit var berichtenCache: BerichtenCache

    @Inject
    internal lateinit var sessies: SessieRegister

    private val anderePod by lazy { RedisAanmeldingen(redis, Duration.ofSeconds(30)) }

    private val volgerOpAnderePod by lazy {
        SessieVolger(berichtenCache, anderePod, sessies, Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofSeconds(5))
    }

    // Uniek per test: de Redis-container wordt gedeeld tussen de tests van dit profiel.
    private val ontvanger = Oin(teller.incrementAndGet().toString().padStart(20, '0').takeLast(20))
    private val ander = Oin(teller.incrementAndGet().toString().padStart(20, '0').takeLast(20))

    @AfterEach
    fun stopAnderePod() {
        anderePod.stop()
    }

    @Test
    fun `bijwerken en verwijderen komen aan op elke open berichtenbox van de ontvanger, op elke pod, en niet bij een ander`() {
        val eigenTabblad = sessiecache.startSessie(ontvanger).sessieId
        val tweedeTabblad = sessiecache.startSessie(ontvanger).sessieId
        val vanAnder = sessiecache.startSessie(ander).sessieId
        zetGereed(ontvanger)
        val bericht = bericht()
        berichtenCache.createBericht(bericht, ontvanger).await().atMost(WACHTTIJD)

        val hier = sessiecache.volg(ontvanger, eigenTabblad).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))
        val daar = volgerOpAnderePod.volg(ontvanger, tweedeTabblad).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))
        val anders = volgerOpAnderePod.volg(ander, vanAnder).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))
        listOf(hier, daar, anders).forEach { stream -> wachtTot("VolgenGestart") { SessieGebeurtenis.VolgenGestart in stream.items } }

        sessiecache.werkBerichtBij(ontvanger, MAGAZIJN, bericht.berichtId, Leesstatus.GELEZEN, "Belasting")

        // Eerst de bijwerking laten aankomen: elke volger leest het bericht pas bij het doorgeven, en
        // een verwijdering die daarvoor valt, laat de bijwerking terecht wegvallen.
        listOf(hier, daar).forEach { stream ->
            wachtTot("BerichtGewijzigd") { stream.items.any { it is SessieGebeurtenis.BerichtGewijzigd } }
        }

        sessiecache.verwijder(ontvanger, MAGAZIJN, bericht.berichtId)

        val verwacht = listOf(
            SessieGebeurtenis.VolgenGestart,
            SessieGebeurtenis.BerichtGewijzigd(MAGAZIJN, bericht.berichtId, Leesstatus.GELEZEN, "Belasting"),
            SessieGebeurtenis.BerichtVerwijderd(MAGAZIJN, bericht.berichtId),
        )

        listOf(hier, daar).forEach { stream ->
            wachtTot("BerichtVerwijderd") { stream.items.any { it is SessieGebeurtenis.BerichtVerwijderd } }
            assertEquals(verwacht, stream.items.filterNot { it == SessieGebeurtenis.Hartslag })
        }

        // Ná de eigen streams: wat voor de ander bedoeld was, had er dan al moeten zijn.
        assertEquals(listOf(SessieGebeurtenis.VolgenGestart), anders.items.filterNot { it == SessieGebeurtenis.Hartslag })

        listOf(hier, daar, anders).forEach { it.cancel() }
    }

    @Test
    fun `een verwijdering van een bericht dat al weg was, komt toch door`() {
        // Twee tabbladen verwijderen tegelijk: wie als tweede komt, vindt niets meer, maar de
        // melding verandert niets aan wat de berichtenboxen tonen en mag dus gewoon gaan.
        val tabblad = sessiecache.startSessie(ontvanger).sessieId
        zetGereed(ontvanger)
        val nooitGeweest = UUID.randomUUID()
        val daar = volgerOpAnderePod.volg(ontvanger, tabblad).subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE))
        wachtTot("VolgenGestart") { SessieGebeurtenis.VolgenGestart in daar.items }

        sessiecache.verwijder(ontvanger, MAGAZIJN, nooitGeweest)

        wachtTot("BerichtVerwijderd") { daar.items.any { it is SessieGebeurtenis.BerichtVerwijderd } }
        assertEquals(
            listOf(SessieGebeurtenis.VolgenGestart, SessieGebeurtenis.BerichtVerwijderd(MAGAZIJN, nooitGeweest)),
            daar.items.filterNot { it == SessieGebeurtenis.Hartslag },
        )

        daar.cancel()
    }

    // Na de start: een start zonder andere lopende sessie ruimt eerst de resten op, ook deze status.
    private fun zetGereed(wie: Identificatienummer) {
        berichtenCache.storeAggregationStatus(
            BerichtenCache.cacheKey(wie),
            AggregationStatus(OphalenStatus.GEREED, totaalMagazijnen = 1, geslaagd = 1),
        ).await().atMost(WACHTTIJD)
    }

    private fun bericht() = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = MAGAZIJN,
        afzenderNaam = "Magazijn A",
        ontvanger = ontvanger,
        onderwerp = "Voorlopige aanslag",
        publicatietijdstip = Instant.parse("2026-11-10T09:00:00Z"),
        magazijnId = MAGAZIJN,
        aantalBijlagen = 0,
        status = Leesstatus.ONGELEZEN,
    )

    private fun wachtTot(wat: String, voorwaarde: () -> Boolean) {
        val deadline = System.nanoTime() + WACHTTIJD.toNanos()

        while (!voorwaarde()) {
            check(System.nanoTime() < deadline) { "$wat niet gezien binnen $WACHTTIJD" }

            Thread.sleep(10)
        }
    }

    private companion object {
        val WACHTTIJD: Duration = Duration.ofSeconds(5)
        const val MAGAZIJN = "00000001003214345000"
        private val teller = AtomicLong(System.nanoTime() + 7_000_000)
    }
}
