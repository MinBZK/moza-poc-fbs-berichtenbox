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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
import java.util.UUID

/**
 * De pagineerlus per magazijn. De cardinaliteiten die het gedrag uitlokken: geen berichten, één
 * niet-volle pagina, precies één volle pagina (het grensgeval waarin nog een call volgt), meerdere
 * pagina's, en meer dan de cap.
 */
class MagazijnPaginaLezerTest {

    private val ontvanger = "BSN:999993653"

    /** Ruim genoeg dat de deadline in deze tests nooit de reden is dat de lus stopt. */
    private val ruimBudget: Duration = Duration.ofMinutes(1)

    @Test
    fun `magazijn zonder berichten levert een lege lijst zonder tweede call`() {
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), any(), any()) } returns
            MagazijnBerichtenResponse(emptyList(), totalElements = 0L, totalPages = 0)

        val oogst = lezer(paginaGrootte = 2, cap = 10).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertTrue(oogst.berichten.isEmpty())
        assertFalse(oogst.afgekapt)
        verify(exactly = 1) { client.getBerichten(any(), any(), any(), any()) }
    }

    @ParameterizedTest(name = "{0} berichten in pagina's van 2")
    @ValueSource(ints = [1, 2, 3, 4, 5])
    fun `alle berichten komen precies eenmaal terug, ongeacht hoe ze over pagina's vallen`(aantal: Int) {
        // Eén bericht verbergt het verschil tussen "geeft de eerste pagina" en "loopt door"; bij 2
        // en 4 is de laatste pagina precies vol.
        val client = mockk<MagazijnClient>()
        val alle = berichten(aantal)

        stubPaginas(client, alle, paginaGrootte = 2)

        val oogst = lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertEquals(alle.map { it.berichtId }, oogst.berichten.map { it.berichtId })
        assertFalse(oogst.afgekapt, "binnen de cap valt er niets af te kappen")
        assertEquals(aantal.toLong(), oogst.totaalBeschikbaar)
    }

    @Test
    fun `boven de cap levert de eerste berichten met het afkap-signaal en het totaal`() {
        val client = mockk<MagazijnClient>()
        val alle = berichten(10)

        stubPaginas(client, alle, paginaGrootte = 2)

        val oogst = lezer(paginaGrootte = 2, cap = 4).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertEquals(alle.take(4).map { it.berichtId }, oogst.berichten.map { it.berichtId })
        assertTrue(oogst.afgekapt)
        assertEquals(10L, oogst.totaalBeschikbaar)
        verify(exactly = 2) { client.getBerichten(any(), any(), any(), any()) }
    }

    @Test
    fun `een cap die geen veelvoud van de paginagrootte is, kapt af op de cap zelf`() {
        val client = mockk<MagazijnClient>()

        stubPaginas(client, berichten(10), paginaGrootte = 4)

        val oogst = lezer(paginaGrootte = 4, cap = 6).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertEquals(6, oogst.berichten.size, "nooit meer dan de cap doorgeven")
        assertTrue(oogst.afgekapt)
    }

    @Test
    fun `precies de cap aan berichten is niet afgekapt`() {
        val client = mockk<MagazijnClient>()

        stubPaginas(client, berichten(4), paginaGrootte = 2)

        val oogst = lezer(paginaGrootte = 2, cap = 4).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertEquals(4, oogst.berichten.size)
        assertFalse(oogst.afgekapt, "het magazijn meldt 4 van 4, dus er blijft niets liggen")
    }

    @Test
    fun `zonder totalen pagineert de lus door tot een lege pagina`() {
        // Zonder tellers is een korte pagina geen bewijs van het einde; alleen een lege pagina wel.
        val client = mockk<MagazijnClient>()
        val alle = berichten(3)

        every { client.getBerichten(any(), any(), 0, any()) } returns
            MagazijnBerichtenResponse(alle.subList(0, 2))
        every { client.getBerichten(any(), any(), 1, any()) } returns
            MagazijnBerichtenResponse(alle.subList(2, 3))
        every { client.getBerichten(any(), any(), 2, any()) } returns
            MagazijnBerichtenResponse(emptyList())

        val oogst = lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertEquals(3, oogst.berichten.size)
        assertFalse(oogst.afgekapt)
        assertNull(oogst.totaalBeschikbaar, "geen totaal betekent geen verzonnen getal")
    }

    @Test
    fun `een magazijn dat kleinere pagina's geeft zonder tellers wordt uitgelezen, niet afgekapt`() {
        // Het gevaarlijkste pad: pageSize bijgesteld én geen totaal. Leest de lus een korte pagina
        // dan als einde, dan meldt ze "compleet" over een halve lijst.
        val client = mockk<MagazijnClient>()
        val alle = berichten(5)

        every { client.getBerichten(any(), any(), 0, any()) } returns MagazijnBerichtenResponse(alle.subList(0, 2))
        every { client.getBerichten(any(), any(), 1, any()) } returns MagazijnBerichtenResponse(alle.subList(2, 4))
        every { client.getBerichten(any(), any(), 2, any()) } returns MagazijnBerichtenResponse(alle.subList(4, 5))
        every { client.getBerichten(any(), any(), 3, any()) } returns MagazijnBerichtenResponse(emptyList())

        val oogst = lezer(paginaGrootte = 100, cap = 500).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertEquals(alle.map { it.berichtId }, oogst.berichten.map { it.berichtId })
        assertFalse(oogst.afgekapt)
    }

    @Test
    fun `een totaal dat lager is dan wat het magazijn zelf leverde, telt als onbekend`() {
        // Een teller die lager is dan onze eigen oogst spreekt zichzelf tegen: niet tonen, niet
        // laten meetellen als "er is niet meer".
        val client = mockk<MagazijnClient>()
        val alle = berichten(4)

        every { client.getBerichten(any(), any(), 0, any()) } returns
            MagazijnBerichtenResponse(alle.subList(0, 2), totalElements = 3L, totalPages = 5)
        every { client.getBerichten(any(), any(), 1, any()) } returns
            MagazijnBerichtenResponse(alle.subList(2, 4), totalElements = 3L, totalPages = 5)

        val oogst = lezer(paginaGrootte = 2, cap = 4).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertEquals(4, oogst.berichten.size)
        assertNull(oogst.totaalBeschikbaar, "een totaal onder de eigen oogst is geen getal om te tonen")
    }

    @Test
    fun `een onmogelijk totaal telt als onbekend en gaat niet de lijn op`() {
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), any(), any()) } returns
            MagazijnBerichtenResponse(berichten(2), totalElements = -1L, totalPages = 1)

        val oogst = lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertNull(oogst.totaalBeschikbaar, "een negatief totaal is geen getal om aan de gebruiker te tonen")
    }

    @Test
    fun `een totaal dat maar op de eerste pagina staat, blijft behouden`() {
        val client = mockk<MagazijnClient>()
        val alle = berichten(4)

        every { client.getBerichten(any(), any(), 0, any()) } returns
            MagazijnBerichtenResponse(alle.subList(0, 2), totalElements = 4L)
        every { client.getBerichten(any(), any(), 1, any()) } returns
            MagazijnBerichtenResponse(alle.subList(2, 4), totalElements = null)
        every { client.getBerichten(any(), any(), 2, any()) } returns MagazijnBerichtenResponse(emptyList())

        val oogst = lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertEquals(4L, oogst.totaalBeschikbaar)
        assertFalse(oogst.afgekapt)
    }

    @Test
    fun `een fout op een vervolgpagina laat de hele bevraging falen`() {
        // Alles-of-niets: een half opgehaalde lijst als geslaagd tonen laat post verdwijnen.
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), 0, any()) } returns MagazijnBerichtenResponse(berichten(2))
        every { client.getBerichten(any(), any(), 1, any()) } throws IllegalStateException("magazijn stuk op pagina 2")

        assertThrows<IllegalStateException> {
            lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)
        }
    }

    @Test
    fun `een pagina groter dan gevraagd wordt ook op een vervolgpagina geweigerd`() {
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), 0, any()) } returns MagazijnBerichtenResponse(berichten(2))
        every { client.getBerichten(any(), any(), 1, any()) } returns MagazijnBerichtenResponse(berichten(5))

        assertThrows<MagazijnResponseOverflow> {
            lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)
        }
    }

    @Test
    fun `zonder totalen is een volle laatste pagina op de cap een afkap-signaal`() {
        // Zonder totaal is "is er meer" niet exact te beantwoorden; dan liever te veel melden.
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), any(), any()) } returns
            MagazijnBerichtenResponse(berichten(2))

        val oogst = lezer(paginaGrootte = 2, cap = 2).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertEquals(2, oogst.berichten.size)
        assertTrue(oogst.afgekapt)
    }

    @Test
    fun `totalPages stopt de lus ook als de laatste pagina precies vol is`() {
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), 0, any()) } returns
            MagazijnBerichtenResponse(berichten(2), totalElements = 2L, totalPages = 1)

        val oogst = lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertEquals(2, oogst.berichten.size)
        verify(exactly = 1) { client.getBerichten(any(), any(), any(), any()) }
    }

    @Test
    fun `pagina groter dan gevraagd gooit overflow`() {
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), any(), any()) } returns
            MagazijnBerichtenResponse(berichten(3))

        assertThrows<MagazijnResponseOverflow> {
            lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)
        }
    }

    @Test
    fun `de gevraagde paginagrootte gaat mee de lijn op`() {
        // Zonder deze parameters valt het magazijn terug op zijn eigen default van twintig.
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), any(), any()) } returns MagazijnBerichtenResponse(emptyList())

        lezer(paginaGrootte = 100, cap = 500).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

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

    @Test
    fun `een magazijn dat kleinere pagina's geeft dan gevraagd, kapt niet stil af`() {
        // Een magazijn mag `pageSize` naar zijn eigen maximum bijstellen: de pagina is dan niet vol
        // terwijl er nog van alles ligt. Zonder het totaal zou de lus "alles opgehaald" melden.
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), any(), any()) } returns
            MagazijnBerichtenResponse(berichten(20), totalElements = 340L, totalPages = 17)

        val oogst = lezer(paginaGrootte = 100, cap = 500).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertEquals(20, oogst.berichten.size)
        assertTrue(oogst.afgekapt, "340 beschikbaar, 20 opgehaald: dat moet gemeld worden")
        assertEquals(340L, oogst.totaalBeschikbaar)
    }

    @Test
    fun `een magazijn dat page negeert levert geen dubbele berichten en geen eindeloze lus`() {
        // Steeds dezelfde pagina: zonder uitgang loopt de lus tot de cap door met herhalingen.
        val client = mockk<MagazijnClient>()

        every { client.getBerichten(any(), any(), any(), any()) } returns
            MagazijnBerichtenResponse(berichten(2))

        val oogst = lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertEquals(2, oogst.berichten.size, "geen herhaalde berichten in de oogst")
        assertEquals(2, oogst.berichten.map { it.berichtId }.toSet().size)
        assertTrue(oogst.afgekapt, "we weten niet wat dit magazijn nog meer heeft")
        verify(exactly = 2) { client.getBerichten(any(), any(), any(), any()) }
    }

    @Test
    fun `een bericht dat op twee pagina's staat, komt maar een keer in de oogst`() {
        // Een bericht dat tijdens het pagineren binnenkomt schuift het venster op.
        val client = mockk<MagazijnClient>()
        val alle = berichten(4)

        every { client.getBerichten(any(), any(), 0, any()) } returns
            MagazijnBerichtenResponse(alle.subList(0, 2))
        every { client.getBerichten(any(), any(), 1, any()) } returns
            MagazijnBerichtenResponse(alle.subList(1, 3))
        every { client.getBerichten(any(), any(), 2, any()) } returns
            MagazijnBerichtenResponse(alle.subList(3, 4))
        every { client.getBerichten(any(), any(), 3, any()) } returns MagazijnBerichtenResponse(emptyList())

        val oogst = lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertEquals(alle.map { it.berichtId }, oogst.berichten.map { it.berichtId })
    }

    @Test
    fun `een cap die geen veelvoud van de paginagrootte is, levert nooit meer dan de cap`() {
        // Ook op het einde van de lijst: de laatste pagina kan de oogst over de cap tillen.
        val client = mockk<MagazijnClient>()

        stubPaginas(client, berichten(6), paginaGrootte = 4)

        val oogst = lezer(paginaGrootte = 4, cap = 5).leesAlleBerichten(client, "magazijn-a", ontvanger, ruimBudget)

        assertEquals(5, oogst.berichten.size, "de cap is een harde grens, ook op de laatste pagina")
        assertTrue(oogst.afgekapt)
    }

    @Test
    fun `een verbruikt budget breekt af als timeout, niet als half resultaat`() {
        // De query-timeout onderbreekt deze blokkerende lus niet, dus stopt ze zelf — met een
        // timeout en niet met een halve oogst.
        val client = mockk<MagazijnClient>()

        stubPaginas(client, berichten(10), paginaGrootte = 2)

        assertThrows<TimeoutException> {
            lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, "magazijn-a", ontvanger, Duration.ZERO)
        }

        verify(exactly = 1) { client.getBerichten(any(), any(), any(), any()) }
    }

    @Test
    fun `een lijst die binnen het budget uit is, breekt niet af`() {
        // Grensgeval bij het vorige: de deadline mag niet vuren zodra de lijst compleet is, anders
        // faalt élk magazijn dat toevallig precies op de laatste pagina eindigt.
        val client = mockk<MagazijnClient>()

        stubPaginas(client, berichten(2), paginaGrootte = 2)

        val oogst = lezer(paginaGrootte = 2, cap = 100).leesAlleBerichten(client, "magazijn-a", ontvanger, Duration.ZERO)

        assertEquals(2, oogst.berichten.size)
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
