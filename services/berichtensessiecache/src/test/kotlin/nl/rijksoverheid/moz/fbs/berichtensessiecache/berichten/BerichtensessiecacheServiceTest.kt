package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.core.JsonParseException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.ProcessingException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnBerichtenResponse
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClient
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClientFactory
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResolver
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import jakarta.ws.rs.WebApplicationException
import java.time.Duration
import java.time.Instant
import java.util.UUID

// @QuarkusTest zodat MockK-based unit-coverage in jacoco-quarkus.exec terechtkomt
// (quarkus-jacoco telt alleen @QuarkusTest-paden mee).
@QuarkusTest
@TestProfile(MockedDependenciesProfile::class)
class BerichtensessiecacheServiceTest {

    private val berichtenCache = mockk<BerichtenCache>()
    private val clientFactory = mockk<MagazijnClientFactory>()
    private val resolver = mockk<MagazijnResolver>(relaxed = true)
    // Korte timeouts in unit-tests: outer (3s) > inner (2s) zodat de cross-check
    // groen blijft maar tests niet wachten op het volledige prod-budget.
    private val service = BerichtensessiecacheService(
        berichtenCache,
        clientFactory,
        resolver,
        innerTimeoutSeconds = 2L,
        outerAwaitSeconds = 3L,
        maxBerichtenPerMagazijn = 1000,
    ).also { it.valideerTimeouts() }

    private val ontvanger = Bsn("999993653")
    private val cacheKey = BerichtenCache.cacheKey(ontvanger)

    @Test
    fun `getBerichten retourneert lege pagina bij null cache-result`() {
        every { berichtenCache.getPage(cacheKey, 0, 20, null, ontvanger) } returns Uni.createFrom().nullItem()

        val result = service.getBerichten(0, 20, ontvanger, null).await().indefinitely()

        assertEquals(0, result.berichten.size)
        assertEquals(0, result.page)
        assertEquals(20, result.pageSize)
        assertEquals(0L, result.totalElements)
    }

    @Test
    fun `getBerichten delegeert correct naar cache met afzender`() {
        val expectedPage = BerichtenPage(emptyList(), 0, 20, 0L, 0)
        every { berichtenCache.getPage(cacheKey, 0, 20, "afzender-123", ontvanger) } returns Uni.createFrom().item(expectedPage)

        val result = service.getBerichten(0, 20, ontvanger, "afzender-123").await().indefinitely()

        assertEquals(expectedPage, result)
        verify { berichtenCache.getPage(cacheKey, 0, 20, "afzender-123", ontvanger) }
    }

    @Test
    fun `getBerichten retourneert cache-resultaat wanneer aanwezig`() {
        val bericht = testBericht()
        val expectedPage = BerichtenPage(listOf(bericht), 0, 20, 1L, 1)
        every { berichtenCache.getPage(cacheKey, 0, 20, null, ontvanger) } returns Uni.createFrom().item(expectedPage)

        val result = service.getBerichten(0, 20, ontvanger, null).await().indefinitely()

        assertEquals(1, result.berichten.size)
        assertEquals(bericht.berichtId, result.berichten[0].berichtId)
    }

    @Test
    fun `ophalenBerichten gooit 409 als lock niet verkregen`() {
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(false)

        val ex = assertThrows<WebApplicationException> {
            service.ophalenBerichten(ontvanger)
        }

        assertEquals(409, ex.response.status)
    }

    @Test
    fun `lege resolver-set leidt tot OPHALEN_GEREED met totaal 0`() {
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(emptySet<String>())
        every { clientFactory.getAllClients() } returns emptyMap()
        every { berichtenCache.store(cacheKey, emptyList()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = service.ophalenBerichten(ontvanger).collect().asList().await().atMost(Duration.ofSeconds(5))

        assertEquals(1, events.size)
        assertEquals(EventType.OPHALEN_GEREED, events[0].event)
        assertEquals(0, events[0].totaalBerichten)
        assertEquals(0, events[0].totaalMagazijnen)
        verify { berichtenCache.store(cacheKey, emptyList()) }
    }

    @Test
    fun `ProfielServiceFoutException uit resolver propageert en zet FOUT-status`() {
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns
            Uni.createFrom().failure(ProfielServiceFoutException.upstreamError(500))
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        // ophalenBerichten gooit de fout synchroon omdat resolver.resolve().await() blokkeert.
        assertThrows<ProfielServiceFoutException> {
            service.ophalenBerichten(ontvanger)
        }

        verify {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                match { it.status == OphalenStatus.FOUT },
            )
        }
    }

    @Test
    fun `resolver levert onbekende magazijn-ID werpt IllegalArgumentException en zet FOUT-status`() {
        // Hard falen ipv stil leeg-degraderen; cleanup vóór throw voorkomt lock-TTL-hang.
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("ghost-magazijn"))
        every { clientFactory.getAllClients() } returns emptyMap()
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val ex = assertThrows<IllegalArgumentException> {
            service.ophalenBerichten(ontvanger)
        }

        assertTrue(ex.message!!.contains("ghost-magazijn"), "Was: ${ex.message}")
        verify {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                match { it.status == OphalenStatus.FOUT },
            )
        }
    }

    @Test
    fun `onverwachte RuntimeException uit resolver levert 500 (niet 503) en zet FOUT-status`() {
        // Eigen-bug → 500 (geen Retry-After); 503 zou client onnodig laten retryen.
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns
            Uni.createFrom().failure(IllegalStateException("interne bug"))
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val ex = assertThrows<WebApplicationException> {
            service.ophalenBerichten(ontvanger)
        }

        assertEquals(500, ex.response.status)
        verify {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                match { it.status == OphalenStatus.FOUT },
            )
        }
    }

    @Test
    fun `valideerTimeouts werpt IllegalArgumentException als outer gelijk is aan inner`() {
        // Outer == inner = race → niet-deterministische fout-classificatie. Fail-fast in startup.
        val mis = BerichtensessiecacheService(
            berichtenCache, clientFactory, resolver,
            innerTimeoutSeconds = 2L, outerAwaitSeconds = 2L,
            maxBerichtenPerMagazijn = 1000,
        )

        val ex = assertThrows<IllegalArgumentException> { mis.valideerTimeouts() }

        assertTrue(ex.message!!.contains("outer-await-seconds"), "Was: ${ex.message}")
        assertTrue(ex.message!!.contains("inner-timeout-seconds"), "Was: ${ex.message}")
    }

    @Test
    fun `valideerTimeouts werpt IllegalArgumentException als outer kleiner is dan inner`() {
        val mis = BerichtensessiecacheService(
            berichtenCache, clientFactory, resolver,
            innerTimeoutSeconds = 5L, outerAwaitSeconds = 2L,
            maxBerichtenPerMagazijn = 1000,
        )

        val ex = assertThrows<IllegalArgumentException> { mis.valideerTimeouts() }

        assertTrue(ex.message!!.contains("outer-await-seconds"), "Was: ${ex.message}")
    }

    @Test
    fun `outer-await-timeout op resolver wrapt naar resolverMislukt en zet FOUT-status`() {
        // Resource-hang vóór subscription: outer-await werpt → resolverMislukt.
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns
            Uni.createFrom().emitter<Set<String>> { /* never emit */ }
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val ex = assertThrows<ProfielServiceFoutException> {
            service.ophalenBerichten(ontvanger)
        }

        assertEquals(ProfielServiceFoutException.Categorie.RESOLVER_MISLUKT, ex.categorie)
        // Mutiny's TimeoutException is RuntimeException, niet j.u.c.TimeoutException.
        assertTrue(
            ex.cause is io.smallrye.mutiny.TimeoutException || ex.cause is java.util.concurrent.TimeoutException,
            "Cause moet outer-timeout zijn: ${ex.cause}",
        )
        verify {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                match { it.status == OphalenStatus.FOUT },
            )
        }
    }

    @Test
    fun `cleanup-fail tijdens Profiel-fout laat oorspronkelijke ProfielServiceFoutException winnen`() {
        // Cleanup-fail mag oorspronkelijke ProfielServiceFoutException niet overschrijven (zou 500 → 503-misclassificatie geven).
        val origineel = ProfielServiceFoutException.upstreamError(503)

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().failure(origineel)
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns
            Uni.createFrom().failure(RuntimeException("Redis ook down"))

        val ex = assertThrows<ProfielServiceFoutException> {
            service.ophalenBerichten(ontvanger)
        }

        assertSame(origineel, ex, "Oorspronkelijke exception moet winnen, niet cleanup-fout")
    }

    @Test
    fun `lege resolver-set met store-failure levert 500 en zet FOUT-status`() {
        // Profiel-call gelukt; cache-write faalde → 500 (cache-fout), niet 503 (zou misleiden).
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(emptySet<String>())
        every { clientFactory.getAllClients() } returns emptyMap()
        every { berichtenCache.store(cacheKey, emptyList()) } returns
            Uni.createFrom().failure(RuntimeException("Redis store down"))
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val ex = assertThrows<WebApplicationException> {
            service.ophalenBerichten(ontvanger)
        }

        assertEquals(500, ex.response.status)
        verify {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                match { it.status == OphalenStatus.FOUT },
            )
        }
    }

    @Test
    fun `lock-acquire-fout (Redis I-O onbereikbaar) levert 503 en doet best-effort cleanup`() {
        // Cause-walking detecteert IOException ook als Mutiny 'm wrapt.
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns
            Uni.createFrom().failure(java.io.IOException("Redis connection lost"))
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val ex = assertThrows<WebApplicationException> {
            service.ophalenBerichten(ontvanger)
        }

        assertEquals(503, ex.response.status)
        verify {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                match { it.status == OphalenStatus.FOUT },
            )
        }
    }

    @Test
    fun `lock-acquire-fout (onverwachte RuntimeException, geen IO of timeout) levert 500`() {
        // Eigen-code-bug → 500, geen 503: client retry'd niet op niet-transient fout.
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns
            Uni.createFrom().failure(NullPointerException("interne bug"))
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val ex = assertThrows<WebApplicationException> {
            service.ophalenBerichten(ontvanger)
        }

        assertEquals(500, ex.response.status)
        verify {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                match { it.status == OphalenStatus.FOUT },
            )
        }
    }

    @Test
    fun `lock-acquire JSON-serialisatie-fout (cause-gewrapt) levert 500 niet 503`() {
        // Cause-walking moet JPE-cause door Mutiny-wrap heen alsnog naar 500 routeren.
        val jpe = com.fasterxml.jackson.core.JsonParseException(null, "Onleesbare status")
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns
            Uni.createFrom().failure(RuntimeException("wrapper", jpe))
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val ex = assertThrows<WebApplicationException> {
            service.ophalenBerichten(ontvanger)
        }

        assertEquals(500, ex.response.status)
    }

    @Test
    fun `magazijn JsonProcessingException levert schema-drift foutmelding in MagazijnEvent`() {
        // Schema-drift moet eigen-foutmelding krijgen, niet als generieke netwerkfout maskeren.
        val client = mockk<MagazijnClient>()
        val parseFout = JsonParseException(null, "Unexpected token at position 42")

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("magazijn-a"))
        every { clientFactory.getAllClients() } returns mapOf("magazijn-a" to client)
        every { clientFactory.getNaam("magazijn-a") } returns "Test magazijn A"
        every { client.getBerichten(any(), any()) } throws ProcessingException(parseFout)
        every { berichtenCache.updateAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.store(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = service.ophalenBerichten(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(15))

        val voltooidEvent = events.first { it.event == EventType.MAGAZIJN_BEVRAGING_VOLTOOID }

        assertEquals(MagazijnStatus.FOUT, voltooidEvent.status)
        assertNotNull(voltooidEvent.foutmelding)
        assertTrue(
            voltooidEvent.foutmelding!!.contains("schema-drift"),
            "Foutmelding moet schema-drift signaleren, niet generieke netwerk-fout: ${voltooidEvent.foutmelding}",
        )
    }

    @Test
    fun `addBericht retourneert het bericht zelf`() {
        val bericht = testBericht()
        every { berichtenCache.addBericht(bericht, ontvanger) } returns Uni.createFrom().voidItem()

        val result = service.addBericht(bericht, ontvanger).await().indefinitely()

        assertNotNull(result)
        assertEquals(bericht.berichtId, result.berichtId)
    }

    @Test
    fun `getBerichtById delegeert naar cache`() {
        val bericht = testBericht()
        every { berichtenCache.getById(bericht.berichtId, ontvanger) } returns Uni.createFrom().item(bericht)

        val result = service.getBerichtById(bericht.berichtId, ontvanger).await().indefinitely()

        assertNotNull(result)
        assertEquals(bericht.berichtId, result!!.berichtId)
    }

    @Test
    fun `updateBerichtStatus delegeert naar cache`() {
        val bericht = testBericht()
        val updated = bericht.copy(status = "GELEZEN")
        every { berichtenCache.updateStatus(bericht.berichtId, ontvanger, "GELEZEN") } returns Uni.createFrom().item(updated)

        val result = service.updateBerichtStatus(bericht.berichtId, ontvanger, "GELEZEN").await().indefinitely()

        assertNotNull(result)
        assertEquals("GELEZEN", result!!.status)
    }

    @Test
    fun `updateAggregationStatus(BEZIG, totaalMagazijnen) fail levert 503 en FOUT-cleanup`() {
        // Tweede status-write na geslaagde lock; cleanup moet lock vrijgeven, anders 60s hang.
        val client = mockk<MagazijnClient>(relaxed = true)

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("magazijn-a"))
        every { clientFactory.getAllClients() } returns mapOf("magazijn-a" to client)
        every { clientFactory.getNaam("magazijn-a") } returns "Magazijn A"
        every { berichtenCache.updateAggregationStatus(cacheKey, any()) } returns
            Uni.createFrom().failure(RuntimeException("Redis update faalde"))
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val ex = assertThrows<WebApplicationException> {
            service.ophalenBerichten(ontvanger)
        }

        assertEquals(503, ex.response.status)
        verify {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                match { it.status == OphalenStatus.FOUT },
            )
        }
    }

    @Test
    fun `cache double-fail (store + status fail) produceert OPHALEN_FOUT event`() {
        // Pipeline moet event emitten (niet hangen, niet 500-throwen naar SSE).
        // Lock leunt dan op Redis-TTL — geaccepteerd, beter dan oneindig vasthouden.
        val client = mockk<MagazijnClient>()

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("magazijn-a"))
        every { clientFactory.getAllClients() } returns mapOf("magazijn-a" to client)
        every { clientFactory.getNaam("magazijn-a") } returns "Magazijn A"
        every { client.getBerichten(any(), any()) } returns MagazijnBerichtenResponse(emptyList())
        every { berichtenCache.updateAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.store(cacheKey, any()) } returns
            Uni.createFrom().failure(RuntimeException("Redis store down"))
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns
            Uni.createFrom().failure(RuntimeException("Redis status ook down"))

        val events = service.ophalenBerichten(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(15))

        val fouten = events.filter { it.event == EventType.OPHALEN_FOUT }

        assertEquals(1, fouten.size, "Verwacht 1 OPHALEN_FOUT-event in: $events")
        assertEquals("Interne fout bij opslaan van resultaten", fouten[0].foutmelding)
    }

    @Test
    fun `magazijn-response boven size-cap wordt geweigerd met overflow-foutmelding`() {
        // cap=2, magazijn levert 3 → FOUT + overflow-foutmelding.
        val serviceMetLageCap = BerichtensessiecacheService(
            berichtenCache, clientFactory, resolver,
            innerTimeoutSeconds = 2L, outerAwaitSeconds = 3L,
            maxBerichtenPerMagazijn = 2,
        ).also { it.valideerTimeouts() }

        val client = mockk<MagazijnClient>()
        val drieBerichten = (1..3).map { i ->
            testBericht().copy(berichtId = UUID.fromString("00000000-0000-0000-0000-00000000000$i"))
        }

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("magazijn-a"))
        every { clientFactory.getAllClients() } returns mapOf("magazijn-a" to client)
        every { clientFactory.getNaam("magazijn-a") } returns "Magazijn A"
        every { client.getBerichten(any(), any()) } returns MagazijnBerichtenResponse(drieBerichten)
        every { berichtenCache.updateAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.store(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = serviceMetLageCap.ophalenBerichten(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(15))

        val voltooid = events.first { it.event == EventType.MAGAZIJN_BEVRAGING_VOLTOOID }

        assertEquals(MagazijnStatus.FOUT, voltooid.status)
        assertNotNull(voltooid.foutmelding)
        assertTrue(
            voltooid.foutmelding!!.contains("te veel berichten"),
            "Foutmelding moet overflow signaleren: ${voltooid.foutmelding}",
        )
    }

    @Test
    fun `raw RuntimeException uit MagazijnClient levert generieke foutmelding (geen recon-leak)`() {
        // BIO 14.1.3: generiek bericht aan eindgebruiker; bug-vs-upstream alleen in log.
        val client = mockk<MagazijnClient>()

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("magazijn-a"))
        every { clientFactory.getAllClients() } returns mapOf("magazijn-a" to client)
        every { clientFactory.getNaam("magazijn-a") } returns "Magazijn A"
        every { client.getBerichten(any(), any()) } throws NullPointerException("gegenereerde client NPE")
        every { berichtenCache.updateAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.store(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = service.ophalenBerichten(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(15))

        val voltooid = events.first { it.event == EventType.MAGAZIJN_BEVRAGING_VOLTOOID }

        assertEquals(MagazijnStatus.FOUT, voltooid.status)
        assertEquals("Magazijn kon niet geraadpleegd worden", voltooid.foutmelding)
    }

    @Test
    fun `boundary - response exact op max-berichten-cap is succesvol (cap is strikt groter dan)`() {
        // Pinned off-by-one: cap-check is `>` (strikt), niet `>=`.
        val serviceMetLageCap = BerichtensessiecacheService(
            berichtenCache, clientFactory, resolver,
            innerTimeoutSeconds = 2L, outerAwaitSeconds = 3L,
            maxBerichtenPerMagazijn = 2,
        ).also { it.valideerTimeouts() }

        val client = mockk<MagazijnClient>()
        val tweeBerichten = (1..2).map { i ->
            testBericht().copy(berichtId = UUID.fromString("00000000-0000-0000-0000-00000000000$i"))
        }

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("magazijn-a"))
        every { clientFactory.getAllClients() } returns mapOf("magazijn-a" to client)
        every { clientFactory.getNaam("magazijn-a") } returns "Magazijn A"
        every { client.getBerichten(any(), any()) } returns MagazijnBerichtenResponse(tweeBerichten)
        every { berichtenCache.updateAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.store(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = serviceMetLageCap.ophalenBerichten(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(15))

        val voltooid = events.first { it.event == EventType.MAGAZIJN_BEVRAGING_VOLTOOID }

        assertEquals(MagazijnStatus.OK, voltooid.status)
        assertEquals(2, voltooid.aantalBerichten)
    }

    private fun testBericht() = Bericht(
        berichtId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        afzender = "00000001234567890000",
        ontvanger = ontvanger.waarde,
        onderwerp = "Test bericht",
        tijdstip = Instant.parse("2026-03-10T10:00:00Z"),
        magazijnId = "magazijn-a",
    )
}
