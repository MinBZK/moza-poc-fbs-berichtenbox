package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * De storing die dit adresseert trad pas op bij honderd organisaties: alle HSET+EXPIRE-commando's
 * werden tegelijk aangeboden en overschreden de Vert.x-wachtrij, waarna de ophaalronde in de
 * laatste stap omviel. Deze test reproduceert dat mechanisme zonder honderd magazijnen na te
 * bootsen: het profiel verlaagt `max-waiting-handlers` naar 64, zodat 200 berichten (400
 * commando's) al ruim over de grens gaan wanneer ze ongebatcht aangeboden worden.
 */
@QuarkusTest
@TestProfile(KleineBatchRedisTestProfile::class)
class RedisBerichtenCacheBatchIntegrationTest {

    @Inject
    internal lateinit var berichtenCache: BerichtenCache

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    // OIN als test-ontvanger: geen elfproef-vereiste, uniek per test-run.
    private val ontvanger = Oin(System.nanoTime().toString().padStart(20, '0').takeLast(20))
    private val cacheKey = BerichtenCache.cacheKey(ontvanger)

    private fun bericht(index: Int) = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = "00000001800866472000",
        ontvanger = ontvanger,
        onderwerp = "Bericht $index",
        inhoud = "Inhoud van bericht $index",
        publicatietijdstip = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index.toLong()),
        magazijnId = "00000001800866472000",
        aantalBijlagen = 0,
    )

    @Test
    fun `store van meer berichten dan de wachtrij aankan slaagt en bewaart alles`() {
        val berichten = (1..200).map { bericht(it) }

        berichtenCache.store(cacheKey, berichten).await().atMost(Duration.ofSeconds(30))

        val opgeslagen = redis.list(String::class.java)
            .lrange("$cacheKey:list", 0, -1)
            .await().atMost(Duration.ofSeconds(5))

        assertEquals(200, opgeslagen.size, "de volledige lijst moet bewaard zijn")
    }

    @Test
    fun `elke per-bericht hash krijgt een TTL, ook voorbij de eerste batch`() {
        val berichten = (1..200).map { bericht(it) }

        berichtenCache.store(cacheKey, berichten).await().atMost(Duration.ofSeconds(30))

        // Het laatste bericht valt in de laatste batch: als alleen de eerste batch verwerkt zou
        // zijn, mist juist deze hash of zijn TTL.
        val laatste = berichten.last()
        val ttl = redis.key().ttl(BerichtenCache.berichtKey(laatste.berichtId))
            .await().atMost(Duration.ofSeconds(5))

        assertTrue(ttl > 0, "hash van het laatste bericht moet bestaan met een TTL; was: $ttl")
    }

    @Test
    fun `de transactie blijft atomair over batches heen`() {
        // Alles of niets: na een geslaagde store moet elk bericht individueel opvraagbaar zijn,
        // niet alleen de kop van de lijst.
        val berichten = (1..200).map { bericht(it) }

        berichtenCache.store(cacheKey, berichten).await().atMost(Duration.ofSeconds(30))

        val gevonden = berichten.count { b ->
            berichtenCache.getById(b.berichtId, ontvanger).await().atMost(Duration.ofSeconds(5)) != null
        }

        assertEquals(200, gevonden, "elk bericht uit elke batch moet opvraagbaar zijn")
    }
}
