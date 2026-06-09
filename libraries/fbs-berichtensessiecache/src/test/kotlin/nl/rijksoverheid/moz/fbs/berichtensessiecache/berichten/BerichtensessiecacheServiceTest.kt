package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonProcessingException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.ProcessingException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnBericht
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnBerichtenResponse
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnFault
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
    private val limieten = object : BerichtLimieten {
        override fun maxBijlagen() = 100
        override fun maxBijlageNaamLengte() = 255
    }
    private val validator = BerichtValidator(limieten)
    private val resolver = mockk<MagazijnResolver>(relaxed = true)
    // Korte timeouts in unit-tests: outer (3s) > inner (2s) zodat de cross-check
    // groen blijft maar tests niet wachten op het volledige prod-budget.
    private val service = BerichtensessiecacheService(
        berichtenCache,
        clientFactory,
        validator,
        resolver,
        innerTimeoutSeconds = 2L,
        outerAwaitSeconds = 3L,
        maxBerichtenPerMagazijn = 1000,
        magazijnQueryTimeoutSeconds = 10L,
        magazijnReadTimeoutMs = 12000L,
        cacheAwaitTimeoutSeconds = 5L,
    ).also { it.valideerTimeouts() }

    private val ontvanger = Bsn("999993653")
    private val cacheKey = BerichtenCache.cacheKey(ontvanger)

    @Test
    fun `getBerichten retourneert lege pagina bij null cache-result`() {
        every { berichtenCache.getPage(cacheKey, 0, 20, null, ontvanger, null) } returns Uni.createFrom().nullItem()

        val result = service.getBerichten(0, 20, ontvanger, null, null).await().indefinitely()

        assertEquals(0, result.berichten.size)
        assertEquals(0, result.page)
        assertEquals(20, result.pageSize)
        assertEquals(0L, result.totalElements)
    }

    @Test
    fun `getBerichten delegeert correct naar cache met afzender`() {
        val expectedPage = BerichtenPagina(emptyList(), 0, 20, 0L, 0)
        every { berichtenCache.getPage(cacheKey, 0, 20, "afzender-123", ontvanger, null) } returns Uni.createFrom().item(expectedPage)

        val result = service.getBerichten(0, 20, ontvanger, "afzender-123", null).await().indefinitely()

        assertEquals(expectedPage, result)
        verify { berichtenCache.getPage(cacheKey, 0, 20, "afzender-123", ontvanger, null) }
    }

    @Test
    fun `getBerichten delegeert map-filter door naar cache`() {
        val expectedPage = BerichtenPagina(emptyList(), 0, 20, 0L, 0)
        every { berichtenCache.getPage(cacheKey, 0, 20, null, ontvanger, "werk") } returns Uni.createFrom().item(expectedPage)

        val result = service.getBerichten(0, 20, ontvanger, null, "werk").await().indefinitely()

        assertEquals(expectedPage, result)
        verify { berichtenCache.getPage(cacheKey, 0, 20, null, ontvanger, "werk") }
    }

    @Test
    fun `getBerichten retourneert cache-resultaat wanneer aanwezig`() {
        val bericht = testBericht()
        val expectedPage = BerichtenPagina(listOf(bericht.toSamenvatting()), 0, 20, 1L, 1)
        every { berichtenCache.getPage(cacheKey, 0, 20, null, ontvanger) } returns Uni.createFrom().item(expectedPage)

        val result = service.getBerichten(0, 20, ontvanger, null, null).await().indefinitely()

        assertEquals(1, result.berichten.size)
        assertEquals(bericht.berichtId, result.berichten[0].berichtId)
    }

    @Test
    fun `haalBerichtenOp gooit 409 als lock niet verkregen`() {
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(false)

        val ex = assertThrows<WebApplicationException> {
            service.haalBerichtenOp(ontvanger)
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

        val events = service.haalBerichtenOp(ontvanger).collect().asList().await().atMost(Duration.ofSeconds(5))

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

        // haalBerichtenOp gooit de fout synchroon omdat resolver.resolve().await() blokkeert.
        assertThrows<ProfielServiceFoutException> {
            service.haalBerichtenOp(ontvanger)
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
            service.haalBerichtenOp(ontvanger)
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
            service.haalBerichtenOp(ontvanger)
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
            berichtenCache, clientFactory, validator, resolver,
            innerTimeoutSeconds = 2L, outerAwaitSeconds = 2L,
            maxBerichtenPerMagazijn = 1000,
            magazijnQueryTimeoutSeconds = 10L,
            magazijnReadTimeoutMs = 12000L,
            cacheAwaitTimeoutSeconds = 5L,
        )

        val ex = assertThrows<IllegalArgumentException> { mis.valideerTimeouts() }

        assertTrue(ex.message!!.contains("outer-await-seconds"), "Was: ${ex.message}")
        assertTrue(ex.message!!.contains("inner-timeout-seconds"), "Was: ${ex.message}")
    }

    @Test
    fun `valideerTimeouts werpt IllegalArgumentException als outer kleiner is dan inner`() {
        val mis = BerichtensessiecacheService(
            berichtenCache, clientFactory, validator, resolver,
            innerTimeoutSeconds = 5L, outerAwaitSeconds = 2L,
            maxBerichtenPerMagazijn = 1000,
            magazijnQueryTimeoutSeconds = 10L,
            magazijnReadTimeoutMs = 12000L,
            cacheAwaitTimeoutSeconds = 5L,
        )

        val ex = assertThrows<IllegalArgumentException> { mis.valideerTimeouts() }

        assertTrue(ex.message!!.contains("outer-await-seconds"), "Was: ${ex.message}")
    }

    @Test
    fun `valideerTimeouts werpt IllegalArgumentException als read-timeout niet strikt groter is dan query-timeout`() {
        // De socket-read-timeout MOET het query-timeout-budget overschrijden, anders kapt de
        // socket een nog-lopende magazijn-call af vóór de Mutiny ifNoItem en krijgt de client
        // een ruwe client-fout i.p.v. een TIMEOUT-event. read=10000ms == query=10s×1000 is niet
        // strikt groter en moet de boot laten falen (pin op de `>`-grens, geen `>=`).
        val mis = BerichtensessiecacheService(
            berichtenCache, clientFactory, validator, resolver,
            innerTimeoutSeconds = 2L, outerAwaitSeconds = 3L,
            maxBerichtenPerMagazijn = 1000,
            magazijnQueryTimeoutSeconds = 10L,
            magazijnReadTimeoutMs = 10_000L,
            cacheAwaitTimeoutSeconds = 5L,
        )

        val ex = assertThrows<IllegalArgumentException> { mis.valideerTimeouts() }

        assertTrue(ex.message!!.contains("read-timeout-ms"), "Was: ${ex.message}")
        assertTrue(ex.message!!.contains("magazijn-query-timeout-seconds"), "Was: ${ex.message}")
    }

    @Test
    fun `valideerTimeouts werpt IllegalArgumentException als inner-timeout 0 is`() {
        // 0/negatief schakelt de bescherming stil uit (Mutiny atMost(ZERO) = onbegrensd wachten).
        val mis = BerichtensessiecacheService(
            berichtenCache, clientFactory, validator, resolver,
            innerTimeoutSeconds = 0L, outerAwaitSeconds = 25L,
            maxBerichtenPerMagazijn = 1000,
            magazijnQueryTimeoutSeconds = 10L,
            magazijnReadTimeoutMs = 12000L,
            cacheAwaitTimeoutSeconds = 5L,
        )

        val ex = assertThrows<IllegalArgumentException> { mis.valideerTimeouts() }

        assertTrue(ex.message!!.contains("inner-timeout-seconds"), "Was: ${ex.message}")
    }

    @Test
    fun `valideerTimeouts werpt IllegalArgumentException als magazijn-query-timeout 0 is`() {
        val mis = BerichtensessiecacheService(
            berichtenCache, clientFactory, validator, resolver,
            innerTimeoutSeconds = 2L, outerAwaitSeconds = 3L,
            maxBerichtenPerMagazijn = 1000,
            magazijnQueryTimeoutSeconds = 0L,
            magazijnReadTimeoutMs = 12000L,
            cacheAwaitTimeoutSeconds = 5L,
        )

        val ex = assertThrows<IllegalArgumentException> { mis.valideerTimeouts() }

        assertTrue(ex.message!!.contains("magazijn-query-timeout-seconds"), "Was: ${ex.message}")
    }

    @Test
    fun `valideerTimeouts werpt IllegalArgumentException als cache-await-timeout 0 is`() {
        val mis = BerichtensessiecacheService(
            berichtenCache, clientFactory, validator, resolver,
            innerTimeoutSeconds = 2L, outerAwaitSeconds = 3L,
            maxBerichtenPerMagazijn = 1000,
            magazijnQueryTimeoutSeconds = 10L,
            magazijnReadTimeoutMs = 12000L,
            cacheAwaitTimeoutSeconds = 0L,
        )

        val ex = assertThrows<IllegalArgumentException> { mis.valideerTimeouts() }

        assertTrue(ex.message!!.contains("cache-await-timeout-seconds"), "Was: ${ex.message}")
    }

    @Test
    fun `outer-await-timeout op resolver wrapt naar resolverMislukt en zet FOUT-status`() {
        // Resource-hang vóór subscription: outer-await werpt → resolverMislukt.
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns
            Uni.createFrom().emitter<Set<String>> { /* never emit */ }
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val ex = assertThrows<ProfielServiceFoutException> {
            service.haalBerichtenOp(ontvanger)
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
            service.haalBerichtenOp(ontvanger)
        }

        assertSame(origineel, ex, "Oorspronkelijke exception moet winnen, niet cleanup-fout")
    }

    @Test
    fun `lege resolver-set met store-failure emit OPHALEN_FOUT-event met referentie en zet FOUT-status`() {
        // SSE-stream is al gestart bij empty-resolver-set; client krijgt OPHALEN_FOUT-event
        // i.p.v. mid-stream HTTP-500. Referentie verbindt event naar cleanup-log.
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(emptySet<String>())
        every { clientFactory.getAllClients() } returns emptyMap()
        every { berichtenCache.store(cacheKey, emptyList()) } returns
            Uni.createFrom().failure(RuntimeException("Redis store down"))
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(5))

        assertEquals(1, events.size)
        assertEquals(EventType.OPHALEN_FOUT, events[0].event)
        assertEquals(0, events[0].totaalMagazijnen)
        assertNotNull(events[0].referentie)
        org.junit.jupiter.api.Assertions.assertDoesNotThrow {
            UUID.fromString(events[0].referentie!!)
        }
        assertTrue(
            events[0].foutmelding!!.contains("(ref: ${events[0].referentie})"),
            "Foutmelding moet de (ref: <UUID>)-suffix dragen die het referentie-veld spiegelt; was: ${events[0].foutmelding}",
        )
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
            service.haalBerichtenOp(ontvanger)
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
            service.haalBerichtenOp(ontvanger)
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
            service.haalBerichtenOp(ontvanger)
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

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
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
    fun `createBericht retourneert het bericht zelf`() {
        val bericht = testBericht()
        every { berichtenCache.createBericht(bericht, ontvanger) } returns Uni.createFrom().voidItem()

        val result = service.createBericht(bericht, ontvanger).await().indefinitely()

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
    fun `updateBerichtMetadata delegeert status-update naar cache`() {
        val bericht = testBericht()
        val updated = bericht.copy(status = Leesstatus.GELEZEN)
        every { berichtenCache.updateBerichtMetadata(bericht.berichtId, ontvanger, "GELEZEN", null) } returns Uni.createFrom().item(updated)

        val result = service.updateBerichtMetadata(bericht.berichtId, ontvanger, "GELEZEN", null).await().indefinitely()

        assertNotNull(result)
        assertEquals(Leesstatus.GELEZEN, result!!.status)
    }

    @Test
    fun `updateBerichtMetadata delegeert map-update naar cache`() {
        val bericht = testBericht()
        val updated = bericht.copy(map = "archief")
        every { berichtenCache.updateBerichtMetadata(bericht.berichtId, ontvanger, null, "archief") } returns Uni.createFrom().item(updated)

        val result = service.updateBerichtMetadata(bericht.berichtId, ontvanger, null, "archief").await().indefinitely()

        assertNotNull(result)
        assertEquals("archief", result!!.map)
    }

    @Test
    fun `updateBerichtMetadata delegeert gecombineerde update naar cache`() {
        val bericht = testBericht()
        val updated = bericht.copy(status = Leesstatus.GELEZEN, map = "archief")
        every { berichtenCache.updateBerichtMetadata(bericht.berichtId, ontvanger, "gelezen", "archief") } returns Uni.createFrom().item(updated)

        val result = service.updateBerichtMetadata(bericht.berichtId, ontvanger, "gelezen", "archief").await().indefinitely()

        assertNotNull(result)
        assertEquals(Leesstatus.GELEZEN, result!!.status)
        assertEquals("archief", result.map)
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
            service.haalBerichtenOp(ontvanger)
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

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(15))

        val fouten = events.filter { it.event == EventType.OPHALEN_FOUT }

        assertEquals(1, fouten.size, "Verwacht 1 OPHALEN_FOUT-event in: $events")
        assertNotNull(fouten[0].referentie, "OPHALEN_FOUT moet referentie dragen voor support-correlatie")
        org.junit.jupiter.api.Assertions.assertDoesNotThrow {
            UUID.fromString(fouten[0].referentie!!)
        }
        // De referentie staat zowel in de foutmelding-tekst ("(ref: ...)") als in het
        // gestructureerde referentie-veld; de tekst meldt expliciet dat eerder getoonde
        // per-magazijn-resultaten niet bewaard zijn.
        assertTrue(
            fouten[0].foutmelding!!.startsWith("Resultaten konden niet worden opgeslagen"),
            "Foutmelding moet melden dat resultaten niet bewaard zijn; was: ${fouten[0].foutmelding}",
        )
        assertTrue(
            fouten[0].foutmelding!!.contains("(ref: ${fouten[0].referentie})"),
            "Foutmelding moet (ref: <UUID>)-suffix dragen; was: ${fouten[0].foutmelding}",
        )
    }

    @Test
    fun `magazijn-response boven size-cap wordt geweigerd met overflow-foutmelding`() {
        // cap=2, magazijn levert 3 → FOUT + overflow-foutmelding.
        val serviceMetLageCap = BerichtensessiecacheService(
            berichtenCache, clientFactory, validator, resolver,
            innerTimeoutSeconds = 2L, outerAwaitSeconds = 3L,
            maxBerichtenPerMagazijn = 2,
            magazijnQueryTimeoutSeconds = 10L,
            magazijnReadTimeoutMs = 12000L,
            cacheAwaitTimeoutSeconds = 5L,
        ).also { it.valideerTimeouts() }

        val client = mockk<MagazijnClient>()
        val drieBerichten = (1..3).map { i ->
            testMagazijnBericht().copy(berichtId = UUID.fromString("00000000-0000-0000-0000-00000000000$i"))
        }

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("magazijn-a"))
        every { clientFactory.getAllClients() } returns mapOf("magazijn-a" to client)
        every { clientFactory.getNaam("magazijn-a") } returns "Magazijn A"
        every { client.getBerichten(any(), any()) } returns MagazijnBerichtenResponse(drieBerichten)
        every { berichtenCache.updateAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.store(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = serviceMetLageCap.haalBerichtenOp(ontvanger).collect().asList()
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

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(15))

        val voltooid = events.first { it.event == EventType.MAGAZIJN_BEVRAGING_VOLTOOID }

        assertEquals(MagazijnStatus.FOUT, voltooid.status)
        assertEquals("Magazijn kon niet geraadpleegd worden", voltooid.foutmelding)
    }

    @Test
    fun `boundary - response exact op max-berichten-cap is succesvol (cap is strikt groter dan)`() {
        // Pinned off-by-one: cap-check is `>` (strikt), niet `>=`.
        val serviceMetLageCap = BerichtensessiecacheService(
            berichtenCache, clientFactory, validator, resolver,
            innerTimeoutSeconds = 2L, outerAwaitSeconds = 3L,
            maxBerichtenPerMagazijn = 2,
            magazijnQueryTimeoutSeconds = 10L,
            magazijnReadTimeoutMs = 12000L,
            cacheAwaitTimeoutSeconds = 5L,
        ).also { it.valideerTimeouts() }

        val client = mockk<MagazijnClient>()
        val tweeBerichten = (1..2).map { i ->
            testMagazijnBericht().copy(berichtId = UUID.fromString("00000000-0000-0000-0000-00000000000$i"))
        }

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("magazijn-a"))
        every { clientFactory.getAllClients() } returns mapOf("magazijn-a" to client)
        every { clientFactory.getNaam("magazijn-a") } returns "Magazijn A"
        every { client.getBerichten(any(), any()) } returns MagazijnBerichtenResponse(tweeBerichten)
        every { berichtenCache.updateAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.store(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = serviceMetLageCap.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(15))

        val voltooid = events.first { it.event == EventType.MAGAZIJN_BEVRAGING_VOLTOOID }

        assertEquals(MagazijnStatus.OK, voltooid.status)
        assertEquals(2, voltooid.aantalBerichten)
    }

    @Test
    fun `bericht met ongeldige ontvanger wordt gedropt zonder de magazijn-aggregatie te laten falen`() {
        // Partial-failure: toBericht bouwt nu het gevalideerde ontvanger-domeintype en kan gooien
        // (ongeldige elfproef). Eén pathologisch bericht mag de hele magazijn-bevraging niet kapot
        // maken — het wordt gedropt en de rest komt door (status OK).
        val client = mockk<MagazijnClient>()
        val geldig = testMagazijnBericht().copy(berichtId = UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val ongeldig = testMagazijnBericht().copy(
            berichtId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            ontvanger = MagazijnBericht.MagazijnOntvanger("BSN", "123456789"),
        )

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("magazijn-a"))
        every { clientFactory.getAllClients() } returns mapOf("magazijn-a" to client)
        every { clientFactory.getNaam("magazijn-a") } returns "Magazijn A"
        every { client.getBerichten(any(), any()) } returns MagazijnBerichtenResponse(listOf(geldig, ongeldig))
        every { berichtenCache.updateAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.store(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(15))

        val voltooid = events.first { it.event == EventType.MAGAZIJN_BEVRAGING_VOLTOOID }

        assertEquals(MagazijnStatus.OK, voltooid.status)
        assertEquals(1, voltooid.aantalBerichten)
    }

    @Test
    fun `lock-acquire JSON-fout via 2-niveau cause-wrap classificeert nog steeds als 500`() {
        // Productie-pad is CompletionException → SyncFailure → JPE (>=2 niveaus).
        // Pinned dat hasCauseOf de volledige chain afloopt (geen 1-niveau-shortcut).
        val jpe = com.fasterxml.jackson.core.JsonParseException(null, "diep genest")
        val gewrapt = RuntimeException("outer", RuntimeException("middle", jpe))

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns
            Uni.createFrom().failure(gewrapt)
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val ex = assertThrows<WebApplicationException> {
            service.haalBerichtenOp(ontvanger)
        }

        assertEquals(500, ex.response.status)
    }

    @Test
    fun `lock-acquire-fout (cause-gewrapt InterruptedException) levert 503 en herstelt interrupt-flag`() {
        // Pod-shutdown tijdens lock-acquire: classifier moet INTERRUPTED detecteren en de
        // interrupt-flag herstellen voor bovenliggende graceful-shutdown.
        val gewrapt = RuntimeException("wrap", InterruptedException("pod-shutdown"))
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns
            Uni.createFrom().failure(gewrapt)
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val ex = assertThrows<WebApplicationException> {
            service.haalBerichtenOp(ontvanger)
        }

        assertEquals(503, ex.response.status)
        assertTrue(Thread.interrupted(), "Interrupt-flag moet zijn hersteld na catch")
    }

    @Test
    fun `lock-acquire-fout (TimeoutException-cause) levert 503 met timeout-melding`() {
        val gewrapt = RuntimeException("wrap", java.util.concurrent.TimeoutException("await overschreden"))
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns
            Uni.createFrom().failure(gewrapt)
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val ex = assertThrows<WebApplicationException> {
            service.haalBerichtenOp(ontvanger)
        }

        assertEquals(503, ex.response.status)
        assertTrue(
            ex.message!!.contains("timeout", ignoreCase = true),
            "Foutmelding moet timeout-specifiek zijn: ${ex.message}",
        )
    }

    @Test
    fun `ConnectException uit MagazijnClient classificeert als NETWORK met generieke foutmelding`() {
        val client = mockk<MagazijnClient>()

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("magazijn-a"))
        every { clientFactory.getAllClients() } returns mapOf("magazijn-a" to client)
        every { clientFactory.getNaam("magazijn-a") } returns "Magazijn A"
        every { client.getBerichten(any(), any()) } throws java.net.ConnectException("connection refused")
        every { berichtenCache.updateAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.store(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(15))

        val voltooid = events.first { it.event == EventType.MAGAZIJN_BEVRAGING_VOLTOOID }

        assertEquals(MagazijnStatus.FOUT, voltooid.status)
        assertEquals("Magazijn kon niet geraadpleegd worden", voltooid.foutmelding)
    }

    @Test
    fun `magazijn-response met 0 berichten is succesvol (boundary)`() {
        val client = mockk<MagazijnClient>()

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("magazijn-a"))
        every { clientFactory.getAllClients() } returns mapOf("magazijn-a" to client)
        every { clientFactory.getNaam("magazijn-a") } returns "Magazijn A"
        every { client.getBerichten(any(), any()) } returns MagazijnBerichtenResponse(emptyList())
        every { berichtenCache.updateAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.store(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(15))

        val voltooid = events.first { it.event == EventType.MAGAZIJN_BEVRAGING_VOLTOOID }

        assertEquals(MagazijnStatus.OK, voltooid.status)
        assertEquals(0, voltooid.aantalBerichten)
    }

    @Test
    fun `CONFIG_DRIFT exception uit resolver emit OPHALEN_FOUT-event ipv 503 en geeft lock vrij`() {
        // Resolver gooit configDrift wanneer alle opt-in OINs onbekend zijn. Service
        // mag NIET 503 throwen (Profiel werkt prima); moet zichtbaar OPHALEN_FOUT-
        // event emitten + lock vrijgeven via storeAggregationStatus(FOUT), anders
        // blijft een volgende request 60s vastlopen op de lock.
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns
            Uni.createFrom().failure(ProfielServiceFoutException.configDrift())
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(5))

        assertEquals(1, events.size)
        assertEquals(EventType.OPHALEN_FOUT, events[0].event)
        assertTrue(
            events[0].foutmelding!!.contains("configuratie"),
            "Foutmelding moet config-mismatch noemen: ${events[0].foutmelding}",
        )
        // referentie-veld voor support-correlatie moet geldige UUID-string zijn,
        // én moet als substring in foutmelding voorkomen (riem-en-bretels invariant).
        assertNotNull(events[0].referentie)
        org.junit.jupiter.api.Assertions.assertDoesNotThrow {
            UUID.fromString(events[0].referentie!!)
        }
        // Strakker dan contains() — pint de "(ref: <id>)"-suffix-conventie vast die
        // de service-comment expliciet belooft; voorkomt stille refactor naar prefix/midden.
        assertTrue(
            events[0].foutmelding!!.endsWith("(ref: ${events[0].referentie})"),
            "foutmelding moet '(ref: <id>)'-suffix hebben: ${events[0].foutmelding}",
        )
        verify {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                match { it.status == OphalenStatus.FOUT },
            )
        }
    }

    @Test
    fun `classifyMagazijnFault termineert op steeds-nieuwe-wrapper cause-getter (depth-cap)`() {
        // IdentityHashMap helpt niet als elke cause-call een NIEUW object van dezelfde
        // overridden-cause-subklasse retourneert. Pathologische chain groeit anders
        // onbegrensd; depth-cap moet afkappen.
        class GrowingThrowable : Throwable("growing") {
            override val cause: Throwable get() = GrowingThrowable()
        }

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            service.classifyMagazijnFault(GrowingThrowable())
        }
    }

    @Test
    fun `classifyMagazijnFault termineert binnen 1s op circular cause-chain`() {
        // Zonder seen-set zou cause-walk oneindig loopen → SSE-stream vast.
        // Twee cycle-varianten: self-cycle (test-mock) + mutual cycle (proxy-wrap).
        val selfCycle = object : Throwable("self") {
            override val cause: Throwable get() = this
        }

        val e1 = RuntimeException("first")
        val e2 = RuntimeException("second")
        e1.initCause(e2)
        e2.initCause(e1)

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            service.classifyMagazijnFault(selfCycle)
        }
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            service.classifyMagazijnFault(e1)
        }
    }

    @Test
    fun `classifyMagazijnFault matched root-class (cause==null edge)`() {
        // causeChain() includes ex zelf als chain[0]; chain.hasCauseOf(X) moet dus
        // matchen wanneer root direct van type X is en cause==null. Eerdere refactor
        // verwijderde `ex is X` directe checks; deze test borgt dat causeChain dat dekt.
        assertEquals(MagazijnFault.TIMEOUT, service.classifyMagazijnFault(io.smallrye.mutiny.TimeoutException()))
        assertEquals(MagazijnFault.MALFORMED, service.classifyMagazijnFault(JsonParseException(null, "bare")))
        assertEquals(MagazijnFault.INTERNAL_BUG, service.classifyMagazijnFault(RuntimeException("bare")))
    }

    @Test
    fun `classifyMagazijnFault doorloopt cause-chain exact EEN keer per call`() {
        // Regressie-guard: P3-refactor verving N losse hasCauseOf-walks (elk depth-cap-warnf
        // kandidaat → Loki-storm) door één causeChain()-materialisatie + N instanceof-checks
        // tegen de cached list. Counting-Throwable telt cause-accesses; één walk ≈ MAX_CAUSE_DEPTH,
        // een refactor terug naar per-check-walks zou een veelvoud opleveren.
        val counter = java.util.concurrent.atomic.AtomicInteger()

        class CountingGrowingThrowable : Throwable("counting") {
            override val cause: Throwable get() {
                counter.incrementAndGet()
                return CountingGrowingThrowable()
            }
        }

        service.classifyMagazijnFault(CountingGrowingThrowable())

        // Eén walk hoogstens MAX_CAUSE_DEPTH (32) cause-accesses; ruime bovengrens 50
        // dekt eventuele toekomstige cap-verhoging, maar vlagt ≥2 walks (>=64).
        assertTrue(
            counter.get() < 50,
            "Verwacht 1 causeChain-walk (≈32 cause-accesses), maar telde ${counter.get()} — refactor terug naar per-check-walks?",
        )
    }

    @Test
    fun `classifyLockAcquireError termineert binnen 1s op circular cause-chain`() {
        // classifyLockAcquireError deelt causeChain() met classifyMagazijnFault;
        // symmetrische cycle-test voorkomt regressie bij toekomstige divergentie.
        val selfCycle = object : Throwable("self") {
            override val cause: Throwable get() = this
        }

        val e1 = RuntimeException("first")
        val e2 = RuntimeException("second")
        e1.initCause(e2)
        e2.initCause(e1)

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            service.classifyLockAcquireError(selfCycle)
        }
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            service.classifyLockAcquireError(e1)
        }
    }

    private fun testBericht() = Bericht(
        berichtId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        afzender = "00000001234567890000",
        ontvanger = ontvanger,
        onderwerp = "Test bericht",
        inhoud = "Inhoud van het bericht",
        publicatietijdstip = Instant.parse("2026-03-10T10:00:00Z"),
        magazijnId = "magazijn-a",
        aantalBijlagen = 0,
    )

    // Magazijn-wire-vorm (MagazijnBericht) voor fetch-fixtures; de service mapt deze via
    // toBericht naar het cache-domein. Getypeerde ontvanger conform de magazijn-spec.
    private fun testMagazijnBericht() = MagazijnBericht(
        berichtId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        afzender = "00000001234567890000",
        ontvanger = MagazijnBericht.MagazijnOntvanger("BSN", ontvanger.waarde),
        onderwerp = "Test bericht",
        inhoud = "Inhoud van het bericht",
        publicatietijdstip = Instant.parse("2026-03-10T10:00:00Z"),
    )
}
