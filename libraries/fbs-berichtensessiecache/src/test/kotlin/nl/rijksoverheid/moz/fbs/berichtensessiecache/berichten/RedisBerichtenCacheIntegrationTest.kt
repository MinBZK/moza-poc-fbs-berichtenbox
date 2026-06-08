package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

@QuarkusTest
@TestProfile(RealRedisTestProfile::class)
class RedisBerichtenCacheIntegrationTest {

    @Inject
    internal lateinit var berichtenCache: BerichtenCache

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    // OIN gebruikt als test-ontvanger: geen elfproef-vereiste, 20-cijferig uniek per test-run.
    private val ontvangerWaarde = System.nanoTime().toString().padStart(20, '0').takeLast(20)
    private val ontvanger = Oin(ontvangerWaarde)

    private fun listKey() = "${cacheKey()}:list"

    private fun cacheKey() = BerichtenCache.cacheKey(ontvanger)

    private fun testBerichten() = listOf(
        Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000001234567890000",
            ontvanger = ontvanger.waarde,
            onderwerp = "Eerste bericht over belastingaangifte",
            inhoud = "Inhoud eerste bericht",
            publicatietijdstip = Instant.parse("2026-03-10T10:00:00Z"),
            magazijnId = "magazijn-a",
            aantalBijlagen = 0,
            map = "werk",
        ),
        Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000009876543210000",
            ontvanger = ontvanger.waarde,
            onderwerp = "Tweede bericht over subsidie",
            inhoud = "Inhoud tweede bericht",
            publicatietijdstip = Instant.parse("2026-03-10T12:00:00Z"),
            magazijnId = "magazijn-a",
            aantalBijlagen = 2,
            map = "prive",
        ),
        Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000001234567890000",
            ontvanger = ontvanger.waarde,
            onderwerp = "Derde bericht over vergunning",
            inhoud = "Inhoud derde bericht",
            publicatietijdstip = Instant.parse("2026-03-10T11:00:00Z"),
            magazijnId = "magazijn-b",
            aantalBijlagen = 1,
            map = "werk",
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
    fun `search filtert op map TAG`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val result = berichtenCache.search(ontvanger, "bericht", 0, 20, null, "prive")
            .await().indefinitely()

        assertTrue(result.berichten.isNotEmpty())
        assertTrue(result.berichten.all { it.map == "prive" })
        assertTrue(result.berichten.none { it.map == "werk" })
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

        // OIN met andere waarde: 20 cijfers, niet geheel nullen
        val andereOntvanger = Oin("00000001003214345000")
        val result = berichtenCache.getById(berichten[0].berichtId, andereOntvanger)
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
        // status-update laat het map-veld ongemoeid (fixture-bericht zit in map 'werk')
        assertEquals(original.map, updated.map)
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

        val result = berichtenCache.updateBerichtMetadata(berichten[0].berichtId, Oin("99999999999999999999"), "gelezen", null)
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
        // Regressie: list-cache bevat JSON-blobs van berichten (via createBericht.rpush /
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
        // Kernbelofte van het optimistic-locking-delete: een createBericht dat gelijktijdig
        // met de delete-rewrite plaatsvindt mag niet door de rewrite worden overschreven.
        // createBericht doet een ongewatchte MULTI/EXEC (altijd toegepast); delete WATCHt de
        // list-key en retryt bij conflict. Het concurrent toegevoegde bericht hoort dus
        // hoe dan ook te overleven, en het doelbericht hoort verwijderd te zijn.
        val berichten = testBerichten().take(2)
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val teVerwijderen = berichten[0]
        val nieuw = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000005555555550000",
            ontvanger = ontvanger.waarde,
            onderwerp = "Concurrent toegevoegd",
            inhoud = "Tijdens delete",
            publicatietijdstip = Instant.parse("2026-03-10T15:00:00Z"),
            magazijnId = "magazijn-a",
            aantalBijlagen = 0,
        )

        // Gelijktijdig afvuren zonder onderlinge ordening.
        val deleteStage = berichtenCache.delete(teVerwijderen.berichtId, ontvanger).subscribeAsCompletionStage()
        val addStage = berichtenCache.createBericht(nieuw, ontvanger).subscribeAsCompletionStage()
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
            ontvanger = ontvanger.waarde,
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
        berichtenCache.delete(target.berichtId, Oin("99999999999999999999")).await().indefinitely()

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
    fun `createBericht voegt toe aan bestaande lijst`() {
        val berichten = testBerichten().take(2)
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val nieuwBericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = "00000005555555550000",
            ontvanger = ontvanger.waarde,
            onderwerp = "Nieuw bericht",
            inhoud = "Inhoud nieuw bericht",
            publicatietijdstip = Instant.parse("2026-03-10T14:00:00Z"),
            magazijnId = "magazijn-c",
            aantalBijlagen = 3,
        )
        berichtenCache.createBericht(nieuwBericht, ontvanger).await().indefinitely()

        val page = berichtenCache.getPage(cacheKey(), 0, 20, null, null).await().indefinitely()
        assertNotNull(page)
        assertEquals(3, page!!.totalElements)
        // `createBericht` doet RPUSH (append) — de LRANGE-volgorde geeft nieuwkomers achteraan.
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
    fun `getPage filtert op map TAG via RediSearch`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val page = berichtenCache.getPage(cacheKey(), 0, 20, null, ontvanger, "werk")
            .await().indefinitely()

        assertNotNull(page)
        assertEquals(2, page!!.berichten.size)
        assertTrue(page.berichten.all { it.map == "werk" })
    }

    @Test
    fun `getPage combineert afzender en map TAG-filters`() {
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        val page = berichtenCache.getPage(cacheKey(), 0, 20, "00000001234567890000", ontvanger, "werk")
            .await().indefinitely()

        assertNotNull(page)
        assertTrue(page!!.berichten.all { it.afzender == "00000001234567890000" && it.map == "werk" })
    }

    @Test
    fun `RediSearch lijst-pad levert samenvatting zonder de zware inhoud-projectie`() {
        // FT.SEARCH op het filter-pad gebruikt een RETURN-lijst beperkt tot de
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
    fun `updateAggregationStatus laat lock-key intact`() {
        val bezigStatus = AggregationStatus(status = OphalenStatus.BEZIG, totaalMagazijnen = 0)

        val lockVerkregen = berichtenCache.trySetAggregationStatus(cacheKey(), bezigStatus).await().indefinitely()

        assertTrue(lockVerkregen, "Eerste trySet moet slagen")

        // updateAggregationStatus werkt de waarde bij maar houdt de lock (key) intact.
        berichtenCache.updateAggregationStatus(
            cacheKey(),
            bezigStatus.copy(totaalMagazijnen = 3),
        ).await().indefinitely()

        val lockNogAanwezig = berichtenCache.trySetAggregationStatus(cacheKey(), bezigStatus).await().indefinitely()

        assertFalse(lockNogAanwezig, "Lock moet nog aanwezig zijn na updateAggregationStatus")
    }

    @Test
    fun `trySetAggregationStatus - lock heeft TTL en verdwijnt automatisch`() {
        // Bewijst dat SET NX EX atomair werkt: na verlopen TTL is de lock vrij
        // zodat een nieuwe aanroep kan slagen (geen eeuwig-lock na EXPIRE-failure).
        val status = AggregationStatus(status = OphalenStatus.BEZIG, totaalMagazijnen = 1)

        val lockVerkregen = berichtenCache.trySetAggregationStatus(cacheKey(), status).await().indefinitely()

        assertTrue(lockVerkregen, "Eerste trySet moet slagen")

        // Tweede aanroep faalt meteen: lock bestaat nog.
        val dubbel = berichtenCache.trySetAggregationStatus(cacheKey(), status).await().indefinitely()

        assertFalse(dubbel, "Lock moet geblokkeerd zijn terwijl hij actief is")

        // TTL is 2s in RealRedisTestProfile; na 3s is de lock automatisch verdwenen.
        Thread.sleep(3_000)

        val naVerloop = berichtenCache.trySetAggregationStatus(cacheKey(), status).await().indefinitely()

        assertTrue(naVerloop, "Lock moet na TTL-verloop opnieuw verkregen kunnen worden")
    }

    @Test
    fun `storeAggregationStatus geeft lock vrij`() {
        val bezigStatus = AggregationStatus(status = OphalenStatus.BEZIG, totaalMagazijnen = 0)

        val lockVerkregen = berichtenCache.trySetAggregationStatus(cacheKey(), bezigStatus).await().indefinitely()

        assertTrue(lockVerkregen, "Eerste trySet moet slagen")

        // storeAggregationStatus schrijft eindstatus en verwijdert de lock-key.
        berichtenCache.storeAggregationStatus(
            cacheKey(),
            AggregationStatus(status = OphalenStatus.GEREED, totaalMagazijnen = 0, geslaagd = 0, mislukt = 0),
        ).await().indefinitely()

        val lockVrij = berichtenCache.trySetAggregationStatus(cacheKey(), bezigStatus).await().indefinitely()

        assertTrue(lockVrij, "Lock moet vrij zijn na storeAggregationStatus")
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

    @Test
    fun `getById werpt CacheCorruptedException bij ontbrekend verplicht veld`() {
        // Schema-drift / corruptie: hash bestaat maar mist een verplicht veld.
        // hashToBericht MOET CacheCorruptedException werpen (niet RuntimeException of upcast),
        // anders wordt het pad in BlockingSessiecache verkeerd geclassificeerd (503 i.p.v. 500).
        val berichtId = UUID.randomUUID()
        val berichtKey = BerichtenCache.berichtKey(berichtId)
        val partialHash = mapOf(
            "berichtId" to berichtId.toString(),
            "afzender" to "00000001234567890000",
            "ontvanger" to ontvanger.waarde,
            "onderwerp" to "test",
            "inhoud" to "inhoud",
            "publicatietijdstip" to "2026-03-10T10:00:00Z",
            // `magazijnId` ontbreekt opzettelijk
            "aantalBijlagen" to "0",
        )

        redis.hash(String::class.java).hset(berichtKey, partialHash).await().indefinitely()

        val ex = assertThrows<CacheCorruptedException> {
            berichtenCache.getById(berichtId, ontvanger).await().indefinitely()
        }

        assertTrue(ex.message!!.contains("magazijnId"), "Was: ${ex.message}")
    }

    @Test
    fun `search werpt CacheCorruptedException bij onparsebaar geindexeerd veld`() {
        // Zelfde corruptie-classificatie als getById, maar dan op het RediSearch-pad:
        // documentToSamenvatting moet een onparsebare waarde als CacheCorruptedException
        // werpen zodat de facade dit als data-issue (500) classificeert en niet als
        // "Redis onbereikbaar" (503). De hash krijgt de bericht-prefix zodat hij in de
        // zoekindex belandt; `publicatietijdstip` is opzettelijk geen geldige Instant.
        val berichtId = UUID.randomUUID()
        val berichtKey = BerichtenCache.berichtKey(berichtId)
        val corrupteHash = mapOf(
            "berichtId" to berichtId.toString(),
            "afzender" to "00000001234567890000",
            "ontvanger" to ontvanger.waarde,
            "onderwerp" to "corrupt zoekdocument",
            "inhoud" to "inhoud",
            "publicatietijdstip" to "geen-geldig-tijdstip",
            "magazijnId" to "magazijn-a",
            "aantalBijlagen" to "0",
        )

        redis.hash(String::class.java).hset(berichtKey, corrupteHash).await().indefinitely()

        // RediSearch indexeert asynchroon: poll tot de zoekopdracht het document raakt.
        // Vóór indexatie levert de query een lege pagina; zodra het corrupte document
        // matcht, MOET documentToSamenvatting CacheCorruptedException werpen.
        val deadline = System.currentTimeMillis() + 5_000
        var corruptie: CacheCorruptedException? = null

        while (System.currentTimeMillis() < deadline && corruptie == null) {
            try {
                val pagina = berichtenCache.search(ontvanger, "corrupt", 0, 20).await().indefinitely()

                assertEquals(0L, pagina.totalElements, "corrupt document mag niet als geldig resultaat terugkomen")
                Thread.sleep(100)
            } catch (e: CacheCorruptedException) {
                corruptie = e
            }
        }

        assertNotNull(corruptie, "verwacht CacheCorruptedException zodra het corrupte document geïndexeerd is")
        assertTrue(corruptie!!.message!!.contains("publicatietijdstip"), "Was: ${corruptie.message}")
    }

    @Test
    fun `getById werpt CacheCorruptedException bij onleesbare UUID-waarde`() {
        val berichtId = UUID.randomUUID()
        val berichtKey = BerichtenCache.berichtKey(berichtId)
        val corruptHash = mapOf(
            "berichtId" to "geen-uuid-meer",
            "afzender" to "00000001234567890000",
            "ontvanger" to ontvanger.waarde,
            "onderwerp" to "test",
            "inhoud" to "inhoud",
            "publicatietijdstip" to "2026-03-10T10:00:00Z",
            "magazijnId" to "magazijn-a",
            "aantalBijlagen" to "0",
        )

        redis.hash(String::class.java).hset(berichtKey, corruptHash).await().indefinitely()

        val ex = assertThrows<CacheCorruptedException> {
            berichtenCache.getById(berichtId, ontvanger).await().indefinitely()
        }

        assertTrue(ex.message!!.contains("berichtId"), "Was: ${ex.message}")
        assertTrue(ex.cause is IllegalArgumentException, "Cause moet UUID-parse-fout zijn: ${ex.cause}")
    }

    @Test
    fun `getById werpt CacheCorruptedException bij onleesbare tijdstip-waarde`() {
        val berichtId = UUID.randomUUID()
        val berichtKey = BerichtenCache.berichtKey(berichtId)
        val corruptHash = mapOf(
            "berichtId" to berichtId.toString(),
            "afzender" to "00000001234567890000",
            "ontvanger" to ontvanger.waarde,
            "onderwerp" to "test",
            "inhoud" to "inhoud",
            "publicatietijdstip" to "niet-een-iso-instant",
            "magazijnId" to "magazijn-a",
            "aantalBijlagen" to "0",
        )

        redis.hash(String::class.java).hset(berichtKey, corruptHash).await().indefinitely()

        val ex = assertThrows<CacheCorruptedException> {
            berichtenCache.getById(berichtId, ontvanger).await().indefinitely()
        }

        assertTrue(ex.message!!.contains("publicatietijdstip"), "Was: ${ex.message}")
        assertTrue(ex.cause is java.time.format.DateTimeParseException, "Cause moet Instant-parse-fout zijn: ${ex.cause}")
    }

    @Test
    fun `getPage met corrupte JSON-entry gooit JsonProcessingException (geen Redis-onbereikbaar-misdiagnose)`() {
        // JPE moet propageren (resource heeft dedicated 500-pad) — niet wegfilteren als
        // "Redis-mislukt" via catch-all (=verkeerde diagnose).
        val key = cacheKey()
        val listKey = "$key:list"

        redis.list(String::class.java).rpush(listKey, "{niet-geldig-json-payload}")
            .await().indefinitely()

        // Mutiny wrapt sync-throws in CompletionException → cause-chain-walk.
        val ex = assertThrows<Throwable> {
            berichtenCache.getPage(key, 0, 20, null, null).await().indefinitely()
        }
        val rootCause = generateSequence(ex as Throwable?) { it.cause }.last()

        assertTrue(
            rootCause is com.fasterxml.jackson.core.JsonProcessingException,
            "Verwachtte JsonProcessingException als root-cause; was: ${rootCause.javaClass.name} (${rootCause.message})",
        )
    }

    @Test
    fun `init is idempotent — herhaalde aanroep dropt bestaande index niet`() {
        // Simuleert rolling-restart-scenario: meerdere pods delen één Redis. Een tweede
        // pod-start (= tweede init-aanroep) mag de index NIET droppen, anders verliezen
        // andere replicas tijdelijk hun search-resultaten.
        val berichten = testBerichten()
        berichtenCache.store(cacheKey(), berichten).await().indefinitely()

        // Verifieer dat berichten vóór de tweede init vindbaar zijn via search.
        val voorTweedeInit = berichtenCache.search(ontvanger, "belastingaangifte", 0, 20, null)
            .await().indefinitely()
        assertTrue(voorTweedeInit.berichten.isNotEmpty(), "Setup-precondities: bericht moet vindbaar zijn")

        // Tweede init() = pod-restart-simulatie. Mag geen drop doen.
        (berichtenCache as RedisBerichtenCache).init()

        // Berichten MOETEN nog steeds vindbaar zijn — als de index gedropt zou zijn,
        // levert search() 0 resultaten op (bestaande hashes zijn niet meer geïndexeerd
        // tot de eerstvolgende store()).
        val naTweedeInit = berichtenCache.search(ontvanger, "belastingaangifte", 0, 20, null)
            .await().indefinitely()
        assertTrue(naTweedeInit.berichten.isNotEmpty(), "Search moet werken na tweede init — index mag niet gedropt zijn")
        assertEquals(voorTweedeInit.berichten.size, naTweedeInit.berichten.size)
    }
}
