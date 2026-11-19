package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.AggregationStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenCache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.sleutel
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.WireMockMagazijnResource
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Het vangnet zelf, tegen een echte Redis met de korte TTL's uit [KortVangnetRedisTestProfile]: wat
 * er gebeurt als niemand actief opruimt, en de grens per ontvanger op een andere waarde dan de
 * standaard. De verloopindex is stelselbreed; elke test begint daarom met een lege index.
 */
@QuarkusTest
@TestProfile(KortVangnetRedisTestProfile::class)
class VangnetVerloopTest {

    @Inject
    lateinit var sessiecache: Sessiecache

    @Inject
    internal lateinit var berichtenCache: BerichtenCache

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    @BeforeEach
    fun legeVerloopindex() {
        redis.key().del(RedisSessieRegister.VERLOOP_INDEX).await().atMost(WACHTTIJD)
    }

    @Test
    fun `zonder actief opruimen verloopt alles na de vangnet-TTL, en houdt de index alleen een kenmerk vast`() {
        val bsn = Bsn("999993653")
        val k = BerichtenCache.cacheKey(bsn)
        redis.key().del("$k:sessies", "$k:sessies:plafond").await().atMost(WACHTTIJD)
        val gestart = System.nanoTime()
        sessiecache.startSessie(bsn)
        val berichten = List(2) { bericht(bsn) }
        berichtenCache.store(k, berichten).await().atMost(WACHTTIJD)
        berichtenCache.storeAggregationStatus(k, AggregationStatus(status = OphalenStatus.GEREED)).await().atMost(WACHTTIJD)
        berichtenCache.delete(berichten[0].sleutel, bsn).await().atMost(WACHTTIJD)

        assertTrue(sleutelsOnder(k).any { ":verwijderd:" in it }, "de tombstone hoort er te zijn: ${sleutelsOnder(k)}")
        // Positieve controle: zonder deze zou een zoekopdracht die nooit iets vindt ook "leeg" opleveren.
        assertTrue(zoekTotaal(bsn) >= 1, "de zoekindex kent vooraf het overgebleven bericht")

        // Peilen in plaats van een vaste wachttijd: de gegevens verlopen een vangnet-TTL na hun laatste
        // schrijfactie, de index een maximale duur na een vangnet-TTL vanaf de start van de sessie. Wat
        // de test langer wacht dan nodig, gaat van het venster daartussen af.
        wachtTotVerlopen(k, berichten)

        assertEquals(0L, zoekTotaal(bsn), "de zoekindex kent nog een verlopen bericht")

        // De index verloopt pas na de gegevens en houdt de ontvanger vast, maar alleen als kenmerk.
        val verstreken = Duration.ofNanos(System.nanoTime() - gestart)
        assertEquals(listOf(BerichtenCache.kenmerk(bsn)), indexLeden(), "index leeg, $verstreken na de start van de sessie")
        assertTrue(indexLeden().none { bsn.waarde in it })

        val gemeld = mutableListOf<Opruiming>()
        sessiecache.ruimVerlopenSessiesOp { gemeld += it }

        assertEquals(listOf(Opruiming.ZonderOntvanger(BerichtenCache.kenmerk(bsn), OpruimUitkomst.GEWIST)), gemeld)
        assertEquals(emptyList<String>(), indexLeden())
    }

    @Test
    fun `de grens per ontvanger volgt de configuratie`() {
        val ontvanger = nieuweOntvanger()
        val eerste = sessiecache.startSessie(ontvanger)
        sessiecache.startSessie(ontvanger)

        assertThrows<SessiecacheException.TeVeelSessies> { sessiecache.startSessie(ontvanger) }

        sessiecache.beeindigSessie(eerste.sessieId, ontvanger)

        assertDoesNotThrow { sessiecache.startSessie(ontvanger) }
    }

    private fun bericht(ontvanger: Identificatienummer) = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = WireMockMagazijnResource.OIN_A,
        afzenderNaam = "Magazijn A",
        ontvanger = ontvanger,
        onderwerp = "Vangnettest",
        publicatietijdstip = Instant.parse("2026-10-21T10:00:00Z"),
        magazijnId = WireMockMagazijnResource.OIN_A,
        aantalBijlagen = 0,
    )

    private fun indexLeden(): List<String> =
        redis.sortedSet(String::class.java).zrange(RedisSessieRegister.VERLOOP_INDEX, 0, -1).await().atMost(WACHTTIJD)

    private fun sleutelsOnder(prefix: String): List<String> =
        redis.execute("KEYS", "$prefix:*").await().atMost(WACHTTIJD).map { it.toString() }

    private fun bestaat(sleutel: String): Boolean = redis.key().exists(sleutel).await().atMost(WACHTTIJD)

    private fun wachtTotVerlopen(prefix: String, berichten: List<Bericht>) {
        val einde = System.nanoTime() + MAX_WACHTEN.toNanos()

        while (sleutelsOnder(prefix).isNotEmpty() || berichten.any { bestaat(BerichtenCache.berichtKey(it.sleutel)) }) {
            assertTrue(System.nanoTime() < einde, "niet verlopen binnen $MAX_WACHTEN: ${sleutelsOnder(prefix)}")
            Thread.sleep(PEILING_MS)
        }
    }

    private fun zoekTotaal(voor: Identificatienummer): Long =
        redis.search().ftSearch(BerichtenCache.SEARCH_INDEX, "@ontvanger:{${voor.waarde}}")
            .await().atMost(WACHTTIJD).count().toLong()

    private companion object {
        val WACHTTIJD: Duration = Duration.ofSeconds(5)

        /** Bovengrens, ruim boven de vangnet-TTL uit [KortVangnetRedisTestProfile]; niet de verwachte wachttijd. */
        val MAX_WACHTEN: Duration = Duration.ofSeconds(15)
        const val PEILING_MS = 50L
        private val teller = AtomicLong(System.nanoTime())

        fun nieuweOntvanger() = Oin(teller.incrementAndGet().toString().padStart(20, '0').takeLast(20))
    }
}
