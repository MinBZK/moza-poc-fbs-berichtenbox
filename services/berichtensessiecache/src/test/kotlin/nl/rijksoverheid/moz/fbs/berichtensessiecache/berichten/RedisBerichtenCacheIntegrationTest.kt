package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.redis.datasource.ReactiveRedisDataSource
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
import java.util.concurrent.CompletableFuture

@QuarkusTest
@TestProfile(RealRedisTestProfile::class)
class RedisBerichtenCacheIntegrationTest {

    @Inject
    lateinit var berichtenCache: BerichtenCache

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    private fun listKey() = "${cacheKey()}:list"

    private val ontvanger = "integration-test-${System.nanoTime()}"

    private fun cacheKey() = BerichtenCache.cacheKey(ontvanger)

    private fun testBerichten() = listOf(
        Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000001234567890000",
            ontvanger = ontvanger,
            onderwerp = "Eerste bericht over belastingaangifte",
            inhoud = "Inhoud eerste bericht",
            publicatietijdstip = Instant.parse("2026-03-10T10:00:00Z"),
            magazijnId = "magazijn-a",
            aantalBijlagen = 0,
        ),
        Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000009876543210000",
            ontvanger = ontvanger,
            onderwerp = "Tweede bericht over subsidie",
            inhoud = "Inhoud tweede bericht",
            publicatietijdstip = Instant.parse("2026-03-10T12:00:00Z"),
            magazijnId = "magazijn-a",
            aantalBijlagen = 2,
        ),
        Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000001234567890000",
            ontvanger = ontvanger,
            onderwerp = "Derde bericht over vergunning",
            inhoud = "Inhoud derde bericht",
            publicatietijdstip = Instant.parse("2026-03-10T11:00:00Z"),
            magazijnId = "magazijn-b",
            aantalBijlagen = 1,
        ),
    )

    @Test
    fun `store en getPage retourneert berichten gesorteerd op publicatietijdstip descending`() {
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
        assertEquals(original.publicatietijdstip, retrieved.publicatietijdstip)
        assertEquals(original.magazijnId, retrieved.magazijnId)
        assertEquals(original.aantalBijlagen, retrieved.aantalBijlagen)
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
    fun `update wijzigt alleen status-veld`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val original = berichten[0]
        val updated = berichtenCache.updateBerichtMetadata(original.berichtId, ontvanger, "GELEZEN", null)
            .await().indefinitely()

        assertNotNull(updated)
        assertEquals(Leesstatus.GELEZEN, updated!!.status)
        assertNull(updated.map)
        assertEquals(original.berichtId, updated.berichtId)
        assertEquals(original.afzender, updated.afzender)
        assertEquals(original.ontvanger, updated.ontvanger)
        assertEquals(original.onderwerp, updated.onderwerp)
        assertEquals(original.publicatietijdstip, updated.publicatietijdstip)
        assertEquals(original.magazijnId, updated.magazijnId)
    }

    @Test
    fun `update wijzigt alleen map-veld zonder status aan te raken`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val original = berichten[0]
        // Zet eerst status zodat we kunnen verifiëren dat een map-only update die niet wist
        berichtenCache.updateBerichtMetadata(original.berichtId, ontvanger, "gelezen", null).await().indefinitely()

        val updated = berichtenCache.updateBerichtMetadata(original.berichtId, ontvanger, null, "archief")
            .await().indefinitely()

        assertNotNull(updated)
        assertEquals(Leesstatus.GELEZEN, updated!!.status)
        assertEquals("archief", updated.map)
    }

    @Test
    fun `update wijzigt status en map gecombineerd`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val original = berichten[0]
        val updated = berichtenCache.updateBerichtMetadata(original.berichtId, ontvanger, "gelezen", "archief")
            .await().indefinitely()

        assertNotNull(updated)
        assertEquals(Leesstatus.GELEZEN, updated!!.status)
        assertEquals("archief", updated.map)
    }

    @Test
    fun `update herschrijft de ongefilterde list-entry zodat GET berichten niet stale is`() {
        // Regressie (H1): de sessie-`list` bevat volledige JSON-blobs die het ongefilterde
        // `getPage` direct deserialiseert. Een HSET-alleen update liet die list stale —
        // oude status/map bleef zichtbaar in `GET /berichten` tot TTL. De update moet de
        // matchende list-entry herschrijven.
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()
        val target = berichten[0]

        berichtenCache.updateBerichtMetadata(target.berichtId, ontvanger, "gelezen", "archief").await().indefinitely()

        // Ongefilterde, list-backed pad (afzender == null → LRANGE, niet RediSearch).
        val page = berichtenCache.getPage(cacheKey(), 0, 50, null, null).await().indefinitely()
        assertNotNull(page)

        val uitLijst = page!!.berichten.single { it.berichtId == target.berichtId }
        assertEquals(Leesstatus.GELEZEN, uitLijst.status, "list-entry toont stale status")
        assertEquals("archief", uitLijst.map, "list-entry toont stale map")
    }

    @Test
    fun `update met verkeerde ontvanger retourneert null`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val result = berichtenCache.updateBerichtMetadata(berichten[0].berichtId, "andere-ontvanger", "gelezen", null)
            .await().indefinitely()

        assertNull(result)
    }

    @Test
    fun `update op niet-bestaand bericht retourneert null`() {
        val result = berichtenCache.updateBerichtMetadata(UUID.randomUUID(), ontvanger, "gelezen", null)
            .await().indefinitely()

        assertNull(result)
    }

    @Test
    fun `delete bestaand bericht verwijdert hash`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val target = berichten[0]
        berichtenCache.delete(target.berichtId, ontvanger).await().indefinitely()

        val result = berichtenCache.getById(target.berichtId, ontvanger).await().indefinitely()
        assertNull(result)
    }

    @Test
    fun `delete bestaand bericht verwijdert ook list-entry`() {
        // Regressie: list-cache bevat JSON-blobs van berichten (via addBericht.rpush /
        // store.rpush). `getPage` deserialiseert direct uit die list — zonder list-prune
        // bleef een verwijderd bericht zichtbaar in `GET /berichten`.
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()
        val target = berichten[0]

        berichtenCache.delete(target.berichtId, ontvanger).await().indefinitely()

        val page = berichtenCache.getPage(cacheKey(), 0, 50, null, null).await().indefinitely()
        assertNotNull(page)
        assertEquals(berichten.size - 1, page!!.berichten.size)
        assertTrue(page.berichten.none { it.berichtId == target.berichtId })
    }

    @Test
    fun `delete laatste bericht laat lege list achter`() {
        val bericht = testBerichten().take(1)
        berichtenCache.store(cacheKey(), bericht).await().indefinitely()

        berichtenCache.delete(bericht[0].berichtId, ontvanger).await().indefinitely()

        val page = berichtenCache.getPage(cacheKey(), 0, 50, null, null).await().indefinitely()
        assertNull(page)
    }

    @Test
    fun `delete onbestaand bericht is no-op`() {
        // Geen exceptie en geen kerneffect — er is simpelweg niets om te verwijderen.
        berichtenCache.delete(UUID.randomUUID(), ontvanger).await().indefinitely()
    }

    @Test
    fun `delete behoudt een concurrent toegevoegd bericht (geen lost-update)`() {
        // Kernbelofte van het optimistic-locking-delete: een addBericht dat gelijktijdig
        // met de delete-rewrite plaatsvindt mag niet door de rewrite worden overschreven.
        // addBericht doet een ongewatchte MULTI/EXEC (altijd toegepast); delete WATCHt de
        // list-key en retryt bij conflict. Het concurrent toegevoegde bericht hoort dus
        // hoe dan ook te overleven, en het doelbericht hoort verwijderd te zijn.
        val berichten = testBerichten().take(2)
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val teVerwijderen = berichten[0]
        val nieuw = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000005555555550000",
            ontvanger = ontvanger,
            onderwerp = "Concurrent toegevoegd",
            inhoud = "Tijdens delete",
            publicatietijdstip = Instant.parse("2026-03-10T15:00:00Z"),
            magazijnId = "magazijn-a",
            aantalBijlagen = 0,
        )

        // Gelijktijdig afvuren zonder onderlinge ordening.
        val deleteStage = berichtenCache.delete(teVerwijderen.berichtId, ontvanger).subscribeAsCompletionStage()
        val addStage = berichtenCache.addBericht(nieuw).subscribeAsCompletionStage()
        CompletableFuture.allOf(deleteStage.toCompletableFuture(), addStage.toCompletableFuture()).join()

        val page = berichtenCache.getPage(cacheKey(), 0, 50, null, null).await().indefinitely()
        assertNotNull(page)
        assertTrue(page!!.berichten.any { it.berichtId == nieuw.berichtId }, "concurrent toegevoegd bericht ging verloren")
        assertTrue(page.berichten.none { it.berichtId == teVerwijderen.berichtId }, "doelbericht niet verwijderd")
    }

    @Test
    fun `delete-prune behoudt een onparsebare list-entry en verwijdert het doelbericht`() {
        // Een corrupte/legacy list-entry (geen geldige JSON) mag de delete-prune niet laten
        // crashen; conservatief behouden (kan per definitie niet het doelbericht zijn).
        val bericht = testBerichten().take(1)
        berichtenCache.store(cacheKey(), bericht).await().indefinitely()

        redis.list(String::class.java).rpush(listKey(), "{geen-geldige-json").await().indefinitely()

        berichtenCache.delete(bericht[0].berichtId, ontvanger).await().indefinitely()

        val entries = redis.list(String::class.java).lrange(listKey(), 0, -1).await().indefinitely()
        assertEquals(listOf("{geen-geldige-json"), entries, "onparsebare entry moet behouden blijven, doelbericht verwijderd")
        assertNull(berichtenCache.getById(bericht[0].berichtId, ontvanger).await().indefinitely())
    }

    @Test
    fun `roundtrip bewaart bijlagen-lijst correct`() {
        val bijlageId = UUID.randomUUID()
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000001234567890000",
            ontvanger = ontvanger,
            onderwerp = "Bericht met bijlage",
            inhoud = "Met bijlage",
            publicatietijdstip = Instant.parse("2026-03-10T13:00:00Z"),
            magazijnId = "magazijn-a",
            aantalBijlagen = 1,
            bijlagen = listOf(BijlageSamenvatting(bijlageId, "factuur.pdf")),
            map = "inkomend",
            status = Leesstatus.ONGELEZEN,
        )
        berichtenCache.store(cacheKey(), listOf(bericht)).await().indefinitely()

        val retrieved = berichtenCache.getById(bericht.berichtId, ontvanger).await().indefinitely()
        assertNotNull(retrieved)
        assertEquals(1, retrieved!!.bijlagen.size)
        assertEquals(bijlageId, retrieved.bijlagen[0].bijlageId)
        assertEquals("factuur.pdf", retrieved.bijlagen[0].naam)
        assertEquals("inkomend", retrieved.map)
        assertEquals(Leesstatus.ONGELEZEN, retrieved.status)
    }

    @Test
    fun `delete met andere ontvanger laat bericht intact`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val target = berichten[0]
        berichtenCache.delete(target.berichtId, "andere-ontvanger").await().indefinitely()

        // Guard moet vóór elke mutatie short-circuiten: zowel de hash (getById) als
        // de list (getPage) van de échte ontvanger blijven volledig intact.
        val result = berichtenCache.getById(target.berichtId, ontvanger).await().indefinitely()
        assertNotNull(result)

        val page = berichtenCache.getPage(cacheKey(), 0, 50, null, null).await().indefinitely()
        assertNotNull(page)
        assertEquals(berichten.size, page!!.berichten.size)
        assertTrue(page.berichten.any { it.berichtId == target.berichtId })
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
            inhoud = "Inhoud nieuw bericht",
            publicatietijdstip = Instant.parse("2026-03-10T14:00:00Z"),
            magazijnId = "magazijn-c",
            aantalBijlagen = 3,
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
    fun `RediSearch lijst-pad levert samenvatting zonder de zware inhoud-projectie`() {
        // M4: FT.SEARCH op het filter-pad gebruikt een RETURN-lijst beperkt tot de
        // samenvatting-velden. `inhoud` en `bijlagen` zitten niet in [BerichtSamenvatting];
        // de samenvatting-velden moeten wél kloppen.
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val page = berichtenCache.getPage(cacheKey(), 0, 20, "00000009876543210000", ontvanger)
            .await().indefinitely()

        assertNotNull(page)
        val bericht = page!!.berichten.single()
        assertEquals("Tweede bericht over subsidie", bericht.onderwerp)
        assertEquals("00000009876543210000", bericht.afzender)
        assertEquals("magazijn-a", bericht.magazijnId)
        assertEquals(2, bericht.aantalBijlagen)
    }

    @Test
    fun `RediSearch zoek-pad levert samenvatting zonder de zware inhoud-projectie`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val result = berichtenCache.search(ontvanger, "subsidie", 0, 20, null)
            .await().indefinitely()

        val bericht = result.berichten.single()
        assertEquals("Tweede bericht over subsidie", bericht.onderwerp)
    }

    @Test
    fun `samenvatting-velden bevatten precies de mapper-velden zonder inhoud of bijlagen`() {
        // Bewaakt dat de RETURN-projectie niet stilletjes `inhoud`/`bijlagen` opneemt
        // (de twee velden die de samenvatting-mapper weggooit).
        assertFalse(RedisBerichtenCache.SAMENVATTING_VELDEN.contains("inhoud"))
        assertFalse(RedisBerichtenCache.SAMENVATTING_VELDEN.contains("bijlagen"))
        assertTrue(RedisBerichtenCache.SAMENVATTING_VELDEN.containsAll(
            listOf("berichtId", "afzender", "ontvanger", "onderwerp", "publicatietijdstip", "magazijnId", "aantalBijlagen", "map", "status"),
        ))
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
