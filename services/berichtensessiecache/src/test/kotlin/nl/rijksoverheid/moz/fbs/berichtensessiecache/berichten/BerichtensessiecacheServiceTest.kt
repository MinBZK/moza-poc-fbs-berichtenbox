package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.Uni
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
        val expectedPage = BerichtenPage(listOf(bericht.toSamenvatting()), 0, 20, 1L, 1)
        every { berichtenCache.getPage(cacheKey, 0, 20, null, ontvanger) } returns Uni.createFrom().item(expectedPage)

        val result = service.getBerichten(0, 20, ontvanger, null).await().indefinitely()

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
        // Drift tussen resolver- en magazijn-config: hard falen i.p.v. stil minder magazijnen
        // bevragen; lock vrijgeven vóór throw voorkomt TTL-hang.
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
    fun `valideerTimeouts werpt IllegalArgumentException als outer gelijk is aan inner`() {
        // Outer == inner = race → niet-deterministische fout-classificatie. Fail-fast in startup.
        val mis = BerichtensessiecacheService(
            berichtenCache, clientFactory, validator, resolver,
            innerTimeoutSeconds = 2L, outerAwaitSeconds = 2L,
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
        // Cleanup-fail mag oorspronkelijke ProfielServiceFoutException niet overschrijven.
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
    fun `cache-fout na aggregatie produceert OPHALEN_FOUT-event`() {
        // store + status-write falen: pipeline moet een OPHALEN_FOUT-event emitten i.p.v.
        // mid-stream te 500-throwen naar de SSE-client.
        val client = mockk<MagazijnClient>()

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("magazijn-a"))
        every { clientFactory.getAllClients() } returns mapOf("magazijn-a" to client)
        every { clientFactory.getNaam("magazijn-a") } returns "Magazijn A"
        every { client.getBerichten(any(), any()) } returns MagazijnBerichtenResponse(emptyList())
        every { berichtenCache.store(cacheKey, any()) } returns
            Uni.createFrom().failure(RuntimeException("Redis store down"))
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns
            Uni.createFrom().failure(RuntimeException("Redis status ook down"))

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(15))

        val fouten = events.filter { it.event == EventType.OPHALEN_FOUT }

        assertEquals(1, fouten.size, "Verwacht 1 OPHALEN_FOUT-event in: $events")
        assertNotNull(fouten[0].foutmelding)
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
        every { berichtenCache.store(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(15))

        val voltooid = events.first { it.event == EventType.MAGAZIJN_BEVRAGING_VOLTOOID }

        assertEquals(MagazijnStatus.FOUT, voltooid.status)
        assertEquals("Magazijn tijdelijk niet bereikbaar", voltooid.foutmelding)
    }

    @Test
    fun `ConnectException uit MagazijnClient levert FOUT met generieke foutmelding`() {
        val client = mockk<MagazijnClient>()

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("magazijn-a"))
        every { clientFactory.getAllClients() } returns mapOf("magazijn-a" to client)
        every { clientFactory.getNaam("magazijn-a") } returns "Magazijn A"
        every { client.getBerichten(any(), any()) } throws java.net.ConnectException("connection refused")
        every { berichtenCache.store(cacheKey, any()) } returns Uni.createFrom().voidItem()
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val events = service.haalBerichtenOp(ontvanger).collect().asList()
            .await().atMost(Duration.ofSeconds(15))

        val voltooid = events.first { it.event == EventType.MAGAZIJN_BEVRAGING_VOLTOOID }

        assertEquals(MagazijnStatus.FOUT, voltooid.status)
        assertEquals("Magazijn tijdelijk niet bereikbaar", voltooid.foutmelding)
    }

    @Test
    fun `magazijn-response met 0 berichten is succesvol (boundary)`() {
        val client = mockk<MagazijnClient>()

        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns Uni.createFrom().item(setOf("magazijn-a"))
        every { clientFactory.getAllClients() } returns mapOf("magazijn-a" to client)
        every { clientFactory.getNaam("magazijn-a") } returns "Magazijn A"
        every { client.getBerichten(any(), any()) } returns MagazijnBerichtenResponse(emptyList())
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
        // Resolver gooit configDrift wanneer alle opt-in OINs onbekend zijn. Service mag NIET
        // 503 throwen (Profiel werkt prima); moet zichtbaar OPHALEN_FOUT emitten + lock vrijgeven
        // via storeAggregationStatus(FOUT), anders blijft een volgende request 60s vastlopen.
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
        assertNotNull(events[0].referentie)
        org.junit.jupiter.api.Assertions.assertDoesNotThrow {
            UUID.fromString(events[0].referentie!!)
        }
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

    private fun testBericht() = Bericht(
        berichtId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        afzender = "00000001234567890000",
        ontvanger = ontvanger.waarde,
        onderwerp = "Test bericht",
        inhoud = "Inhoud van het bericht",
        publicatietijdstip = Instant.parse("2026-03-10T10:00:00Z"),
        magazijnId = "magazijn-a",
        aantalBijlagen = 0,
    )
}
