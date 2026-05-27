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
        // Drift-detector-invariant: resolver leverde IDs die de factory niet kent.
        // ophalenBerichten MOET hard falen i.p.v. stil leeg-degraderen (anders blijft
        // een config-drift tussen resolver en factory ongezien). Cleanup vóór throw
        // zodat de lock niet tot TTL hangt.
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
        // Eigen-code-bug of contract-issue uit het resolver-pad: client moet 500 krijgen
        // (geen Retry-After) zodat retry niet de bug verlengt, en ops weet dat dit géén
        // transient upstream-storing is. 503 zou misleiden ("Profiel-service onbereikbaar").
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
        // Outer MOET strikt groter zijn dan inner zodat de inner-timeout altijd eerst aanslaat.
        // Bij outer == inner wint een race tussen de twee timeouts; de fout-classificatie
        // (timeout vs onbereikbaar) wordt niet-deterministisch. Fail-fast in startup.
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
        // Resolver komt niet binnen outer-budget terug. Mutiny inner-timeout heeft NIET
        // aangeslagen (resource-hang vóór subscription, bv. worker-pool verzadigd).
        // Outer-await werpt j.u.c.TimeoutException → ProfielServiceFoutException.resolverMislukt.
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
        // Lock-cleanup faalt zelf (Redis ook deels down). Caller MOET de oorspronkelijke
        // exception (ProfielServiceFoutException) krijgen — niet de cleanup-exception —
        // anders krijgt de client een verkeerd 500 i.p.v. correcte 503.
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
        // Profiel-call slaagde (resolver kwam terug met emptySet), maar cache-write voor
        // de lege resultaten faalt. 500 (cache-fout), niet 503 ("Profiel onbereikbaar"
        // zou misleiden — de Profiel-call IS gelukt).
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
        // Regressie-vangnet: bij Redis-IO-fout op trySetAggregationStatus.await() (echte
        // connection-loss) kan een partial lock-set zijn. Best-effort cleanup + 503 voor
        // de client. Cause-walking in `classifyLockAcquireError` detecteert IOException
        // in de cause-chain ook als Mutiny 'm in een RuntimeException heeft gewrapt.
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
        // Spiegelt de classify-keuze: een raw RuntimeException zonder IOException-cause
        // (bv. NPE uit de gegenereerde cache-binding) is een eigen-code-bug → 500, geen
        // 503. Client retry'd niet op een niet-transient fout; ops zoekt niet naar Redis-
        // storing die er niet is.
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
        // Mutiny's await wrapt JsonProcessingException in een runtime-container; directe
        // catch-on-class werkt niet. Cause-walking moet het pad alsnog naar 500 routeren
        // (eigen-code-bug, geen Redis-issue).
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
        // H2: schema-drift / contract-mismatch op magazijn-respons MOET zichtbaar zijn
        // als "schema-drift, contact beheerder" — niet als generieke "kon niet geraadpleegd
        // worden" (= eigen-bug zichtbaar laten, niet maskeren als netwerk-fout).
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
        // Regressie-vangnet: lock-acquire én resolver gelukt, maar de tweede status-
        // schrijfactie (totaalMagazijnen invullen) faalt. Caller MOET 503 zien én de
        // lock moet via cleanupLockMetFoutStatus naar FOUT — anders blijft 60s lock-hangen.
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
        // Regressie-vangnet: zowel store(berichten) als storeAggregationStatus(FOUT)
        // falen. Pipeline MOET een OPHALEN_FOUT-event emitten (niet hangen, niet 500-throwen
        // naar SSE-stream); de FATAL+ALERT-log wordt door log-aggregator opgepikt. Lock
        // blijft tot Redis-TTL hangen — geaccepteerd, beter dan oneindig vasthouden.
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
        // Regressie-vangnet: availability-cap blokkeert rogue-magazijn met te grote
        // payload. Service met maxBerichtenPerMagazijn=2 — magazijn levert 3 berichten →
        // MagazijnEvent moet status FOUT + overflow-foutmelding hebben.
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
        // Regressie-vangnet: een NPE/IllegalState uit de gegenereerde client wordt door
        // de classifier als INTERNAL_BUG geclassificeerd → eindgebruiker ziet de generieke
        // "kon niet geraadpleegd worden"-tekst, NIET een "interne fout"-marker die aan
        // een attacker zou laten zien dat een specifiek inputcombo een eigen-code-bug
        // triggert (BIO 14.1.3). Het technisch onderscheid blijft alleen in de applicatielog.
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
        // Regressie-vangnet voor off-by-one: cap-check gebruikt `>` (strikt), niet `>=`.
        // Een refactor naar `>=` zou legitieme responses van precies de cap-grootte
        // onterecht als overflow classificeren. Test pinned de boundary-semantiek.
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
