package nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn.MagazijnClientFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import jakarta.ws.rs.WebApplicationException
import java.time.Instant
import java.util.UUID

class BerichtensessiecacheServiceTest {

    private val berichtenCache = mockk<BerichtenCache>()
    private val clientFactory = mockk<MagazijnClientFactory>()
    private val limieten = object : BerichtLimieten {
        override fun maxBijlagen() = 100
        override fun maxBijlageNaamLengte() = 255
        override fun maxMapnaamLengte() = 64
    }
    private val validator = BerichtValidator(limieten)
    private val service = BerichtensessiecacheService(berichtenCache, clientFactory, validator)

    private val ontvanger = "999993653"
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
        every { clientFactory.getAllClients() } returns emptyMap()
        every { berichtenCache.trySetAggregationStatus(cacheKey, any()) } returns Uni.createFrom().item(false)

        val ex = assertThrows<WebApplicationException> {
            service.haalBerichtenOp(ontvanger)
        }
        assertEquals(409, ex.response.status)
    }

    @Test
    fun `addBericht retourneert het bericht zelf`() {
        val bericht = testBericht()
        every { berichtenCache.addBericht(bericht) } returns Uni.createFrom().voidItem()

        val result = service.addBericht(bericht).await().indefinitely()

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
    fun `updateBericht delegeert status-update naar cache`() {
        val bericht = testBericht()
        val updated = bericht.copy(status = Leesstatus.GELEZEN)
        every { berichtenCache.werkBerichtBij(bericht.berichtId, ontvanger, "GELEZEN", null) } returns Uni.createFrom().item(updated)

        val result = service.updateBericht(bericht.berichtId, ontvanger, "GELEZEN", null).await().indefinitely()

        assertNotNull(result)
        assertEquals(Leesstatus.GELEZEN, result!!.status)
    }

    @Test
    fun `updateBericht delegeert map-update naar cache`() {
        val bericht = testBericht()
        val updated = bericht.copy(map = "archief")
        every { berichtenCache.werkBerichtBij(bericht.berichtId, ontvanger, null, "archief") } returns Uni.createFrom().item(updated)

        val result = service.updateBericht(bericht.berichtId, ontvanger, null, "archief").await().indefinitely()

        assertNotNull(result)
        assertEquals("archief", result!!.map)
    }

    @Test
    fun `updateBericht delegeert gecombineerde update naar cache`() {
        val bericht = testBericht()
        val updated = bericht.copy(status = Leesstatus.GELEZEN, map = "archief")
        every { berichtenCache.werkBerichtBij(bericht.berichtId, ontvanger, "gelezen", "archief") } returns Uni.createFrom().item(updated)

        val result = service.updateBericht(bericht.berichtId, ontvanger, "gelezen", "archief").await().indefinitely()

        assertNotNull(result)
        assertEquals(Leesstatus.GELEZEN, result!!.status)
        assertEquals("archief", result.map)
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
}
