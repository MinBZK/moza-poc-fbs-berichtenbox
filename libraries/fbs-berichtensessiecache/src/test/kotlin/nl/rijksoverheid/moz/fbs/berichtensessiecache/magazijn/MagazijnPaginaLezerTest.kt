package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.UUID

/**
 * De pagineerlus per magazijn. De cardinaliteiten die het gedrag uitlokken: geen berichten, één
 * niet-volle pagina, precies één volle pagina (het grensgeval waarin nog een call volgt), meerdere
 * pagina's, en meer dan de cap.
 */
class MagazijnPaginaLezerTest {

    private val ontvanger = "BSN:999993653"

    @Test
    fun `magazijn zonder berichten levert een lege lijst zonder tweede call`() {
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), any(), any()) } returns
            MagazijnBerichtenResponse(emptyList(), totalElements = 0L, totalPages = 0)

        val oogst = lezer(paginaGrootte = 2, cap = 10).leesAlleBerichten(client, ontvanger)

        assertTrue(oogst.berichten.isEmpty())
        assertFalse(oogst.afgekapt)
        verify(exactly = 1) { client.getBerichten(any(), any(), any(), any()) }
    }

    @ParameterizedTest(name = "{0} berichten in pagina's van 2")
    @ValueSource(ints = [1, 2, 3, 4, 5])
    fun `alle berichten komen precies eenmaal terug, ongeacht hoe ze over pagina's vallen`(aantal: Int) {
        // Een lijst van één verbergt het verschil tussen "geeft de eerste pagina" en "loopt door";
        // 2 en 4 zijn de grensgevallen waarin de laatste pagina precies vol is en er dus nog een
        // (lege) call volgt.
        val client = mockk<MagazijnClient>()
        val alle = berichten(aantal)

        stubPaginas(client, alle, paginaGrootte = 2)

        val oogst = lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, ontvanger)

        assertEquals(alle.map { it.berichtId }, oogst.berichten.map { it.berichtId })
        assertFalse(oogst.afgekapt, "binnen de cap valt er niets af te kappen")
        assertEquals(aantal.toLong(), oogst.totaalBeschikbaar)
    }

    @Test
    fun `boven de cap levert de eerste berichten met het afkap-signaal en het totaal`() {
        val client = mockk<MagazijnClient>()
        val alle = berichten(10)

        stubPaginas(client, alle, paginaGrootte = 2)

        val oogst = lezer(paginaGrootte = 2, cap = 4).leesAlleBerichten(client, ontvanger)

        assertEquals(alle.take(4).map { it.berichtId }, oogst.berichten.map { it.berichtId })
        assertTrue(oogst.afgekapt)
        assertEquals(10L, oogst.totaalBeschikbaar)
        verify(exactly = 2) { client.getBerichten(any(), any(), any(), any()) }
    }

    @Test
    fun `een cap die geen veelvoud van de paginagrootte is, kapt af op de cap zelf`() {
        val client = mockk<MagazijnClient>()

        stubPaginas(client, berichten(10), paginaGrootte = 4)

        val oogst = lezer(paginaGrootte = 4, cap = 6).leesAlleBerichten(client, ontvanger)

        assertEquals(6, oogst.berichten.size, "nooit meer dan de cap doorgeven")
        assertTrue(oogst.afgekapt)
    }

    @Test
    fun `precies de cap aan berichten is niet afgekapt`() {
        val client = mockk<MagazijnClient>()

        stubPaginas(client, berichten(4), paginaGrootte = 2)

        val oogst = lezer(paginaGrootte = 2, cap = 4).leesAlleBerichten(client, ontvanger)

        assertEquals(4, oogst.berichten.size)
        assertFalse(oogst.afgekapt, "het magazijn meldt 4 van 4, dus er blijft niets liggen")
    }

    @Test
    fun `zonder totalen stopt de lus op een niet-volle pagina`() {
        // Een magazijn dat `totalElements`/`totalPages` niet meestuurt: de lus mag daar niet op
        // leunen, anders blijft ze doorvragen of stopt ze te vroeg.
        val client = mockk<MagazijnClient>()
        val alle = berichten(3)

        every { client.getBerichten(any(), any(), 0, any()) } returns
            MagazijnBerichtenResponse(alle.subList(0, 2))
        every { client.getBerichten(any(), any(), 1, any()) } returns
            MagazijnBerichtenResponse(alle.subList(2, 3))

        val oogst = lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, ontvanger)

        assertEquals(3, oogst.berichten.size)
        assertFalse(oogst.afgekapt)
        assertNull(oogst.totaalBeschikbaar, "geen totaal betekent geen verzonnen getal")
    }

    @Test
    fun `zonder totalen is een volle laatste pagina op de cap een afkap-signaal`() {
        // Het magazijn noemt geen totaal, dus "is er meer" is niet exact te beantwoorden. Liever
        // één keer te veel "er is meer" dan post laten verdwijnen zonder het te melden.
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), any(), any()) } returns
            MagazijnBerichtenResponse(berichten(2))

        val oogst = lezer(paginaGrootte = 2, cap = 2).leesAlleBerichten(client, ontvanger)

        assertEquals(2, oogst.berichten.size)
        assertTrue(oogst.afgekapt)
    }

    @Test
    fun `totalPages stopt de lus ook als de laatste pagina precies vol is`() {
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), 0, any()) } returns
            MagazijnBerichtenResponse(berichten(2), totalElements = 2L, totalPages = 1)

        val oogst = lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, ontvanger)

        assertEquals(2, oogst.berichten.size)
        verify(exactly = 1) { client.getBerichten(any(), any(), any(), any()) }
    }

    @Test
    fun `pagina groter dan gevraagd gooit overflow`() {
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), any(), any()) } returns
            MagazijnBerichtenResponse(berichten(3))

        assertThrows<MagazijnResponseOverflow> {
            lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, ontvanger)
        }
    }

    @Test
    fun `de gevraagde paginagrootte gaat mee de lijn op`() {
        // Zonder deze parameters valt het magazijn terug op zijn eigen default van twintig — de
        // oorzaak van het probleem dat deze lezer oplost.
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), any(), any()) } returns MagazijnBerichtenResponse(emptyList())

        lezer(paginaGrootte = 100, cap = 500).leesAlleBerichten(client, ontvanger)

        verify { client.getBerichten(ontvanger, null, 0, 100) }
    }

    @ParameterizedTest(name = "paginagrootte {0}")
    @ValueSource(ints = [0, -1, 101])
    fun `ongeldige paginagrootte laat de bean niet starten`(paginaGrootte: Int) {
        assertThrows<IllegalArgumentException> { lezer(paginaGrootte = paginaGrootte, cap = 100) }
    }

    @Test
    fun `cap van nul laat de bean niet starten`() {
        assertThrows<IllegalArgumentException> { lezer(paginaGrootte = 100, cap = 0) }
    }

    private fun lezer(paginaGrootte: Int, cap: Int) =
        MagazijnPaginaLezer(paginaGrootte = paginaGrootte, maxBerichtenPerMagazijn = cap)

    /** Verdeelt [alle] over pagina's zoals een magazijn dat zou doen, inclusief de tellers. */
    private fun stubPaginas(client: MagazijnClient, alle: List<MagazijnBericht>, paginaGrootte: Int) {
        val paginas = (alle.size + paginaGrootte - 1) / paginaGrootte

        (0..paginas).forEach { paginaNummer ->
            val van = minOf(paginaNummer * paginaGrootte, alle.size)
            val tot = minOf(van + paginaGrootte, alle.size)

            every { client.getBerichten(any(), any(), paginaNummer, any()) } returns
                MagazijnBerichtenResponse(
                    berichten = alle.subList(van, tot),
                    totalElements = alle.size.toLong(),
                    totalPages = paginas,
                )
        }
    }

    private fun berichten(aantal: Int) = (1..aantal).map { volgnummer ->
        MagazijnBericht(
            berichtId = UUID.fromString("00000000-0000-0000-0000-%012d".format(volgnummer)),
            afzender = "00000001234567890000",
            ontvanger = MagazijnBericht.MagazijnOntvanger("BSN", "999993653"),
            onderwerp = "Bericht $volgnummer",
            inhoud = "Inhoud $volgnummer",
            publicatietijdstip = Instant.parse("2026-03-10T10:00:00Z"),
        )
    }
}
