package nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.AggregationStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenCache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.OphalenStatus
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.sleutel
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.WireMockMagazijnResource
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Het vangnet zelf, tegen een echte Redis met een vangnet-TTL van drie seconden uit
 * [KortVangnetRedisTestProfile]: wat er gebeurt als niemand actief opruimt. De verloopindex is
 * stelselbreed; elke test begint daarom met een lege index.
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
        sessiecache.startSessie(bsn)
        val berichten = List(2) { bericht(bsn) }
        berichtenCache.store(k, berichten).await().atMost(WACHTTIJD)
        berichtenCache.storeAggregationStatus(k, AggregationStatus(status = OphalenStatus.GEREED)).await().atMost(WACHTTIJD)
        berichtenCache.delete(berichten[0].sleutel, bsn).await().atMost(WACHTTIJD)

        assertTrue(sleutelsOnder(k).any { ":verwijderd:" in it }, "de tombstone hoort er te zijn: ${sleutelsOnder(k)}")

        Thread.sleep(DATA_TTL_MS + MARGE_MS)

        assertEquals(emptyList<String>(), sleutelsOnder(k))
        berichten.forEach { assertFalse(bestaat(BerichtenCache.berichtKey(it.sleutel)), "${it.sleutel} niet verlopen") }

        // De index heeft geen TTL en houdt de ontvanger vast, maar alleen als kenmerk.
        assertEquals(listOf(BerichtenCache.kenmerk(bsn)), indexLeden())
        assertTrue(indexLeden().none { bsn.waarde in it })

        val gemeld = mutableListOf<Opruiming>()
        sessiecache.ruimVerlopenSessiesOp { gemeld += it }

        assertEquals(listOf(Opruiming.ZonderOntvanger(BerichtenCache.kenmerk(bsn), OpruimUitkomst.GEWIST)), gemeld)
        assertEquals(emptyList<String>(), indexLeden())
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

    private companion object {
        val WACHTTIJD: Duration = Duration.ofSeconds(5)

        // Maximale duur plus marge uit het profiel; de marge hierna dekt de traagheid van de test zelf.
        const val DATA_TTL_MS = 3_000L
        const val MARGE_MS = 700L
    }
}
