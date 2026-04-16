package nl.rijksoverheid.moz.berichtensessiecache.berichten

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@QuarkusTest
@TestProfile(RealRedisTestProfile::class)
class RedisBerichtenCacheIntegrationTest {

    @Inject
    lateinit var berichtenCache: BerichtenCache

    private val ontvanger = "integration-test-${System.nanoTime()}"

    private fun cacheKey() = BerichtenCache.cacheKey(ontvanger)

    private fun testBerichten() = listOf(
        Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000001234567890000",
            ontvanger = ontvanger,
            onderwerp = "Eerste bericht over belastingaangifte",
            tijdstip = Instant.parse("2026-03-10T10:00:00Z"),
            magazijnId = "magazijn-a",
        ),
        Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000009876543210000",
            ontvanger = ontvanger,
            onderwerp = "Tweede bericht over subsidie",
            tijdstip = Instant.parse("2026-03-10T12:00:00Z"),
            magazijnId = "magazijn-a",
        ),
        Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000001234567890000",
            ontvanger = ontvanger,
            onderwerp = "Derde bericht over vergunning",
            tijdstip = Instant.parse("2026-03-10T11:00:00Z"),
            magazijnId = "magazijn-b",
        ),
    )

    @Test
    fun `store en getPage retourneert berichten gesorteerd op tijdstip descending`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val page = berichtenCache.getPage(cacheKey(), 0, 20, null, null).await().indefinitely()

        assertNotNull(page)
        assertEquals(3, page!!.berichten.size)
        assertEquals(berichten[1].berichtId, page.berichten[0].berichtId) // 12:00 eerst
        assertEquals(berichten[2].berichtId, page.berichten[1].berichtId) // 11:00
        assertEquals(berichten[0].berichtId, page.berichten[2].berichtId) // 10:00
    }

    @Test
    fun `search vindt bericht op onderwerp`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val result = berichtenCache.search(ontvanger, "belastingaangifte", 0, 20, null)
            .await().indefinitely()

        assertTrue(result.berichten.isNotEmpty())
        assertTrue(result.berichten.any { it.onderwerp.contains("belastingaangifte") })
    }

    @Test
    fun `search filtert op afzender`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val result = berichtenCache.search(ontvanger, "bericht", 0, 20, "00000009876543210000")
            .await().indefinitely()

        assertTrue(result.berichten.isNotEmpty())
        assertTrue(result.berichten.all { it.afzender == "00000009876543210000" })
    }

    @Test
    fun `trySetAggregationStatus atomaire lock - eerste aanroep slaagt, tweede faalt`() {
        val status = AggregationStatus(status = OphalenStatus.BEZIG, totaalMagazijnen = 2)

        val first = berichtenCache.trySetAggregationStatus(cacheKey(), status).await().indefinitely()
        val second = berichtenCache.trySetAggregationStatus(cacheKey(), status).await().indefinitely()

        assertTrue(first)
        assertFalse(second)
    }

    @Test
    fun `serialisatie roundtrip via getById`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val original = berichten[0]
        val retrieved = berichtenCache.getById(original.berichtId, ontvanger).await().indefinitely()

        assertNotNull(retrieved)
        assertEquals(original.berichtId, retrieved!!.berichtId)
        assertEquals(original.afzender, retrieved.afzender)
        assertEquals(original.ontvanger, retrieved.ontvanger)
        assertEquals(original.onderwerp, retrieved.onderwerp)
        assertEquals(original.tijdstip, retrieved.tijdstip)
        assertEquals(original.magazijnId, retrieved.magazijnId)
    }

    @Test
    fun `getById met verkeerde ontvanger retourneert null`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val result = berichtenCache.getById(berichten[0].berichtId, "andere-ontvanger")
            .await().indefinitely()

        assertNull(result)
    }

    @Test
    fun `updateStatus wijzigt alleen status-veld`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val original = berichten[0]
        val updated = berichtenCache.updateStatus(original.berichtId, ontvanger, "GELEZEN")
            .await().indefinitely()

        assertNotNull(updated)
        assertEquals("GELEZEN", updated!!.status)
        assertEquals(original.berichtId, updated.berichtId)
        assertEquals(original.afzender, updated.afzender)
        assertEquals(original.ontvanger, updated.ontvanger)
        assertEquals(original.onderwerp, updated.onderwerp)
        assertEquals(original.tijdstip, updated.tijdstip)
        assertEquals(original.magazijnId, updated.magazijnId)
    }

    @Test
    fun `addBericht voegt toe aan bestaande lijst`() {
        val berichten = testBerichten().take(2)
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val nieuwBericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000005555555550000",
            ontvanger = ontvanger,
            onderwerp = "Nieuw bericht",
            tijdstip = Instant.parse("2026-03-10T14:00:00Z"),
            magazijnId = "magazijn-c",
        )
        berichtenCache.addBericht(nieuwBericht).await().indefinitely()

        val page = berichtenCache.getPage(cacheKey(), 0, 20, null, null).await().indefinitely()
        assertNotNull(page)
        assertEquals(3, page!!.totalElements)
        // `addBericht` doet RPUSH (append) — de LRANGE-volgorde geeft nieuwkomers achteraan.
        // Positie-check: nieuwBericht zit op index 2 (na de 2 eerder gestoorde berichten).
        val indices = page.berichten.mapIndexed { i, b -> b.berichtId to i }.toMap()
        assertTrue(indices.containsKey(nieuwBericht.berichtId), "nieuwBericht niet in pagina: ${page.berichten.map { it.berichtId }}")
        assertEquals(2, indices[nieuwBericht.berichtId])
        assertEquals(nieuwBericht.onderwerp, page.berichten[2].onderwerp)
        assertEquals(nieuwBericht.afzender, page.berichten[2].afzender)
        assertEquals(nieuwBericht.magazijnId, page.berichten[2].magazijnId)
    }

    @Test
    fun `store met lege lijst verwijdert key`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()
        berichtenCache.store(cacheKey(), emptyList()).await().indefinitely()

        val page = berichtenCache.getPage(cacheKey(), 0, 20, null, null).await().indefinitely()
        assertNull(page)
    }

    @Test
    fun `storeAggregationStatus en getAggregationStatus roundtrip`() {
        val status = AggregationStatus(
            status = OphalenStatus.GEREED,
            totaalMagazijnen = 2,
            geslaagd = 2,
            mislukt = 0,
        )
        berichtenCache.storeAggregationStatus(cacheKey(), status).await().indefinitely()

        val retrieved = berichtenCache.getAggregationStatus(cacheKey()).await().indefinitely()
        assertNotNull(retrieved)
        assertEquals(OphalenStatus.GEREED, retrieved!!.status)
        assertEquals(2, retrieved.geslaagd)
    }

    @Test
    fun `getPage met afzender en ontvanger filter via RediSearch`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val page = berichtenCache.getPage(cacheKey(), 0, 20, "00000001234567890000", ontvanger)
            .await().indefinitely()

        assertNotNull(page)
        assertTrue(page!!.berichten.all { it.afzender == "00000001234567890000" })
    }

    @Test
    fun `TTL verificatie - data verdwijnt na TTL`() {
        val berichten = testBerichten().take(1)
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val before = berichtenCache.getPage(cacheKey(), 0, 20, null, null).await().indefinitely()
        assertNotNull(before)

        // TTL is 2s in RealRedisTestProfile, wacht 3s
        Thread.sleep(3_000)

        val after = berichtenCache.getPage(cacheKey(), 0, 20, null, null).await().indefinitely()
        assertNull(after)
    }

    @Test
    fun `sliding TTL - reads verlengen sessie-keys zodat actieve gebruiker data behoudt`() {
        val berichten = testBerichten().take(1)
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()
        berichtenCache.storeAggregationStatus(
            cacheKey(),
            AggregationStatus(status = OphalenStatus.GEREED, totaalMagazijnen = 1, geslaagd = 1),
        ).await().indefinitely()

        // TTL is 2s; doe 3 reads elk na 1s. Zonder sliding TTL zou de data na 2s weg zijn;
        // met sliding TTL verlengt elke read en blijft de data beschikbaar.
        repeat(3) {
            Thread.sleep(1_000)
            val page = berichtenCache.getPage(cacheKey(), 0, 20, null, null).await().indefinitely()
            assertNotNull(page, "Cache is vroegtijdig verlopen op iteratie $it")
            assertEquals(1, page!!.totalElements)
        }
    }

    @Test
    fun `sliding TTL - getById verlengt TTL van berichthash`() {
        val berichten = testBerichten().take(1)
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()
        val berichtId = berichten[0].berichtId

        repeat(3) {
            Thread.sleep(1_000)
            val bericht = berichtenCache.getById(berichtId, ontvanger).await().indefinitely()
            assertNotNull(bericht, "Bericht-hash is vroegtijdig verlopen op iteratie $it")
        }
    }
}
