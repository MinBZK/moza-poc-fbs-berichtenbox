package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtenLijst
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Link
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.PaginaLinks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class BerichtenlijstServiceTest {

    private val sessiecache: SessiecacheClient = mockk()
    private val service = BerichtenlijstService(sessiecache)

    @Test
    fun `lijst delegeert met juiste headers en query`() {
        val expected = BerichtenLijst()
        every { sessiecache.lijst("BSN:1", 0, 20) } returns expected

        val actual = service.lijst("BSN:1", 0, 20)

        assertSame(expected, actual)
        verify(exactly = 1) { sessiecache.lijst("BSN:1", 0, 20) }
    }

    @Test
    fun `lijst geeft null-parameters door zonder default-invulling`() {
        val expected = BerichtenLijst()
        every { sessiecache.lijst("BSN:1", null, null) } returns expected

        val actual = service.lijst("BSN:1", null, null)

        assertSame(expected, actual)
    }

    @Test
    fun `zoek delegeert q`() {
        val expected = BerichtenLijst()
        every { sessiecache.zoek("BSN:1", "rente") } returns expected

        val actual = service.zoek("BSN:1", "rente")

        assertSame(expected, actual)
        verify(exactly = 1) { sessiecache.zoek("BSN:1", "rente") }
    }

    @Test
    fun `lijst herschrijft sessiecache-paginatieparameters naar uitvraag-namen`() {
        val response = BerichtenLijst().apply {
            links = PaginaLinks().apply {
                self = Link().apply { href = "/api/v1/berichten?page=1&pageSize=20" }
                next = Link().apply { href = "/api/v1/berichten?page=2&pageSize=20" }
                prev = Link().apply { href = "/api/v1/berichten?page=0&pageSize=20" }
            }
        }
        every { sessiecache.lijst("BSN:1", 1, 20) } returns response

        val actual = service.lijst("BSN:1", 1, 20)

        assertEquals("/api/v1/berichten?pagina=1&paginaGrootte=20", actual.links.self.href)
        assertEquals("/api/v1/berichten?pagina=2&paginaGrootte=20", actual.links.next.href)
        assertEquals("/api/v1/berichten?pagina=0&paginaGrootte=20", actual.links.prev.href)
    }

    @Test
    fun `zoek herschrijft paginatieparameters in HAL-links net als lijst`() {
        val response = BerichtenLijst().apply {
            links = PaginaLinks().apply {
                self = Link().apply { href = "/api/v1/berichten/_zoeken?q=rente&page=1&pageSize=20" }
                next = Link().apply { href = "/api/v1/berichten/_zoeken?q=rente&page=2&pageSize=20" }
            }
        }
        every { sessiecache.zoek("BSN:1", "rente") } returns response

        val actual = service.zoek("BSN:1", "rente")

        assertEquals("/api/v1/berichten/_zoeken?q=rente&pagina=1&paginaGrootte=20", actual.links.self.href)
        assertEquals("/api/v1/berichten/_zoeken?q=rente&pagina=2&paginaGrootte=20", actual.links.next.href)
    }

    @Test
    fun `vertaling raakt alleen echte query-parameters, niet een toevallige substring`() {
        // De KDoc belooft dat het anker (`?`/`&`) voorkomt dat een waarde die `page`/
        // `pageSize` als substring bevat (bv. `homepage`) wordt herschreven.
        val response = BerichtenLijst().apply {
            links = PaginaLinks().apply {
                self = Link().apply { href = "/api/v1/berichten?filter=homepage&page=1" }
            }
        }
        every { sessiecache.lijst("BSN:1", 1, 20) } returns response

        val actual = service.lijst("BSN:1", 1, 20)

        assertEquals("/api/v1/berichten?filter=homepage&pagina=1", actual.links.self.href)
    }

    @Test
    fun `vertaling verdraagt een Link met null href`() {
        val response = BerichtenLijst().apply {
            links = PaginaLinks().apply {
                self = Link().apply { href = null }
                next = Link().apply { href = "/api/v1/berichten?page=2&pageSize=20" }
            }
        }
        every { sessiecache.lijst("BSN:1", 2, 20) } returns response

        val actual = service.lijst("BSN:1", 2, 20)

        assertEquals(null, actual.links.self.href)
        assertEquals("/api/v1/berichten?pagina=2&paginaGrootte=20", actual.links.next.href)
    }
}
