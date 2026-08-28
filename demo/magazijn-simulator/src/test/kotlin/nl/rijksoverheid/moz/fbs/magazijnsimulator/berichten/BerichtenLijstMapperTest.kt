package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import jakarta.ws.rs.core.UriBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * De paginering-velden en HAL-links van een lege lijst, los van HTTP.
 *
 * `next` en `prev` horen wég te blijven en niet als `null` in de JSON te belanden: het schema van
 * de gedeelde spec laat afwezige velden toe, maar `null` niet — en dat verschil valt pas op in een
 * contracttest of, erger, bij een consumer.
 */
class BerichtenLijstMapperTest {

    @Test
    fun `een lege lijst telt nul berichten en nul pagina's`() {
        val lijst = BerichtenLijstMapper.leeg(page = 0, pageSize = 20, afzender = null, baseUri = basis())

        assertEquals(emptyList<Any>(), lijst.berichten)
        assertEquals(0, lijst.page)
        assertEquals(20, lijst.pageSize)
        assertEquals(0L, lijst.totalElements)
        assertEquals(0, lijst.totalPages)
    }

    @Test
    fun `zonder buurpagina's blijven next en prev leeg`() {
        val links = BerichtenLijstMapper.leeg(0, 20, null, basis()).links

        assertNull(links.next)
        assertNull(links.prev)
    }

    @Test
    fun `self, first en last wijzen naar de berichten-collectie onder de meegegeven basis`() {
        val links = BerichtenLijstMapper.leeg(0, 20, null, basis()).links

        listOf(links.self, links.first, links.last).forEach { link ->
            assertTrue(
                link.href.startsWith("$BASIS/berichten?"),
                "verwacht een link onder $BASIS/berichten, was ${link.href}",
            )
        }
    }

    @Test
    fun `de gevraagde paginering komt terug in de links`() {
        val links = BerichtenLijstMapper.leeg(page = 0, pageSize = 5, afzender = null, baseUri = basis()).links

        assertEquals("$BASIS/berichten?page=0&pageSize=5", links.self.href)
    }

    @Test
    fun `een afzenderfilter blijft in de links staan zodat de client hem niet kwijtraakt`() {
        val links = BerichtenLijstMapper.leeg(0, 20, AFZENDER, basis()).links

        assertEquals("$BASIS/berichten?page=0&pageSize=20&afzender=$AFZENDER", links.self.href)
    }

    @Test
    fun `zonder afzenderfilter staat die parameter er niet in`() {
        val links = BerichtenLijstMapper.leeg(0, 20, null, basis()).links

        assertEquals("$BASIS/berichten?page=0&pageSize=20", links.self.href)
    }

    private fun basis(): UriBuilder = UriBuilder.fromUri(BASIS)

    private companion object {
        const val BASIS = "http://magazijn-simulator:8092/magazijn/00000009000000000001/api/v1"
        const val AFZENDER = "00000009000000000002"
    }
}
