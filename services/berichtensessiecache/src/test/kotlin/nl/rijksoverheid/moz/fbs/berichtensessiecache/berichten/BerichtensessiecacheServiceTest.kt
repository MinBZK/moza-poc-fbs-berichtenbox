package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClientFactory
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnResolver
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.profiel.ProfielServiceFoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import jakarta.ws.rs.WebApplicationException
import java.time.Duration
import java.time.Instant
import java.util.UUID

class BerichtensessiecacheServiceTest {

    private val berichtenCache = mockk<BerichtenCache>()
    private val clientFactory = mockk<MagazijnClientFactory>()
    private val resolver = mockk<MagazijnResolver>(relaxed = true)
    private val service = BerichtensessiecacheService(berichtenCache, clientFactory, resolver)

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
            Uni.createFrom().failure(ProfielServiceFoutException("upstream 500"))
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
    fun `RuntimeException uit resolver wordt ge-wrapped als ProfielServiceFoutException en zet FOUT-status`() {
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(true)
        every { resolver.resolve(ontvanger) } returns
            Uni.createFrom().failure(RuntimeException("onverwachte fout"))
        every { berichtenCache.storeAggregationStatus(cacheKey, any()) } returns Uni.createFrom().voidItem()

        val ex = assertThrows<ProfielServiceFoutException> {
            service.ophalenBerichten(ontvanger)
        }

        assertTrue(ex.message!!.contains("RuntimeException"), "Wrapped message moet oorspronkelijk type bevatten: ${ex.message}")
        verify {
            berichtenCache.storeAggregationStatus(
                cacheKey,
                match { it.status == OphalenStatus.FOUT },
            )
        }
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

    private fun testBericht() = Bericht(
        berichtId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        afzender = "00000001234567890000",
        ontvanger = ontvanger.waarde,
        onderwerp = "Test bericht",
        tijdstip = Instant.parse("2026-03-10T10:00:00Z"),
        magazijnId = "magazijn-a",
    )
}
