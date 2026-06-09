package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtSamenvatting
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Leesstatus
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class BerichtenlijstServiceTest {

    private val sessiecache: Sessiecache = mockk()
    private val service = BerichtenlijstService(sessiecache)
    private val ontvanger = Bsn("999990019")

    private fun pagina(
        berichten: List<BerichtSamenvatting> = emptyList(),
        page: Int = 0,
        pageSize: Int = 20,
        totalPages: Int = if (berichten.isEmpty()) 0 else 1,
    ) = BerichtenPagina(berichten, page, pageSize, berichten.size.toLong(), totalPages)

    private fun samenvatting(id: UUID = UUID.randomUUID()) = BerichtSamenvatting(
        berichtId = id,
        afzender = "00000001003214345000",
        ontvanger = Bsn("999990019"),
        onderwerp = "Onderwerp",
        publicatietijdstip = Instant.parse("2026-05-26T10:00:00Z"),
        magazijnId = "magazijn-a",
        aantalBijlagen = 2,
        map = "werk",
        status = Leesstatus.ONGELEZEN,
    )

    @Test
    fun `lijst delegeert ontvanger en paginering naar de facade`() {
        every { sessiecache.lijst(ontvanger, 1, 50) } returns pagina(page = 1, pageSize = 50)

        service.lijst("BSN:999990019", 1, 50)

        verify(exactly = 1) { sessiecache.lijst(ontvanger, 1, 50) }
    }

    @Test
    fun `lijst geeft null-parameters door zonder default-invulling`() {
        // Defaults zijn een facade-verantwoordelijkheid; de service mag ze niet
        // dubbel invullen (anders kan de facade-cap stilletjes omzeild raken).
        every { sessiecache.lijst(ontvanger, null, null) } returns pagina()

        service.lijst("BSN:999990019", null, null)

        verify(exactly = 1) { sessiecache.lijst(ontvanger, null, null) }
    }

    @Test
    fun `lijst mapt de domein-samenvatting naar het api-model met self-link`() {
        val id = UUID.randomUUID()

        every { sessiecache.lijst(ontvanger, null, null) } returns pagina(berichten = listOf(samenvatting(id)))

        val lijst = service.lijst("BSN:999990019", null, null)
        val item = lijst.berichten.single()

        assertEquals(id, item.berichtId)
        assertEquals("Onderwerp", item.onderwerp)
        assertEquals("00000001003214345000", item.afzender)
        assertEquals(2, item.aantalBijlagen)
        assertEquals("werk", item.map)
        assertEquals("magazijn-a", item.magazijnId)
        assertEquals("/api/v1/berichten/$id", item.links.self.href)
    }

    @Test
    fun `lijst bouwt pagineringslinks met uitvraag-parameternamen`() {
        every { sessiecache.lijst(ontvanger, 1, 20) } returns pagina(page = 1, totalPages = 3)

        val lijst = service.lijst("BSN:999990019", 1, 20)

        assertEquals("/api/v1/berichten?pagina=1&paginaGrootte=20", lijst.links.self.href)
        assertEquals("/api/v1/berichten?pagina=2&paginaGrootte=20", lijst.links.next.href)
        assertEquals("/api/v1/berichten?pagina=0&paginaGrootte=20", lijst.links.prev.href)
    }

    @Test
    fun `eerste pagina heeft geen prev-link en laatste geen next-link`() {
        every { sessiecache.lijst(ontvanger, 0, 20) } returns pagina(page = 0, totalPages = 2)

        val eerste = service.lijst("BSN:999990019", 0, 20)

        assertNull(eerste.links.prev)
        assertEquals("/api/v1/berichten?pagina=1&paginaGrootte=20", eerste.links.next.href)

        every { sessiecache.lijst(ontvanger, 1, 20) } returns pagina(page = 1, totalPages = 2)

        val laatste = service.lijst("BSN:999990019", 1, 20)

        assertNull(laatste.links.next)
    }

    @Test
    fun `zoek delegeert q en bouwt een self-link met geëncodeerde q`() {
        every { sessiecache.zoek(ontvanger, "rente & meer") } returns pagina()

        val lijst = service.zoek("BSN:999990019", "rente & meer")

        verify(exactly = 1) { sessiecache.zoek(ontvanger, "rente & meer") }
        assertEquals("/api/v1/berichten/_zoeken?q=rente+%26+meer", lijst.links.self.href)
    }

    @Test
    fun `cache-storing wordt 502 en cache-NogNietGevuld propageert 409`() {
        every { sessiecache.lijst(ontvanger, null, null) } throws SessiecacheException.Onbereikbaar("cache weg")

        val storing = assertThrows<WebApplicationException> { service.lijst("BSN:999990019", null, null) }

        assertEquals(502, storing.response.status)

        every { sessiecache.lijst(ontvanger, null, null) } throws SessiecacheException.NogNietGevuld("nog niet opgehaald")

        val conflict = assertThrows<WebApplicationException> { service.lijst("BSN:999990019", null, null) }

        assertEquals(409, conflict.response.status)
    }
}
