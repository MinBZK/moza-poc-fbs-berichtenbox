package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * De reden onder de tellers. Wat hier verkeerd gaat, gaat verkeerd op het moment dat er iemand
 * meekijkt: dan staat er "1 mislukt" met een zin die de bediener naar de verkeerde knop stuurt.
 */
class FaalredenTest {

    @Test
    fun `elke faalmodus krijgt zijn eigen zin`() {
        val redenen = alleFaalmodi()

        assertEquals(redenen.size, redenen.toSet().size, "faalmodi met dezelfde zin: $redenen")
    }

    /**
     * De volledige regel en niet alleen een deel ervan: een reden die zelf op een leesteken eindigt
     * kreeg er een punt achter geplakt ("...storing aan?."), en dat is precies wat er op het scherm
     * staat. Asserties die de verwachting met dezelfde productiefunctie opbouwen, zien dat niet.
     */
    @Test
    fun `de regel voor een onbereikbaar magazijn staat er letterlijk zo`() {
        assertEquals(
            "Reden: magazijn $RVO was niet bereikbaar; mogelijk staat er nog een storing aan.",
            Faalreden.samenvatting(listOf(Faalreden.onbereikbaar(RVO))),
        )
    }

    @Test
    fun `een reden die zelf al afsluit, krijgt er geen tweede leesteken bij`() {
        // Het magazijn levert zijn eigen afwijzing als een afgeronde zin aan.
        val samenvatting = Faalreden.samenvatting(listOf(Faalreden.vanStatus(RVO, 403, "Ontvanger heeft geen profiel.")))

        assertEquals("Reden: magazijn $RVO wees het bericht af (HTTP 403): Ontvanger heeft geen profiel.", samenvatting)
    }

    @Test
    fun `de reden van het magazijn zelf gaat voor op de eigen zin`() {
        // Achter één 403 zitten meerdere oorzaken met een ander vervolg; het magazijn weet welke.
        val eigen = Faalreden.vanStatus(RVO, 403)
        val vanMagazijn = Faalreden.vanStatus(RVO, 403, "Ontvanger heeft geen actieve berichtenbox-voorkeur.")

        assertNotEquals(eigen, vanMagazijn)
        assertTrue(vanMagazijn.contains("geen actieve berichtenbox-voorkeur"), vanMagazijn)
    }

    @Test
    fun `een lege reden van het magazijn valt terug op de eigen zin`() {
        assertEquals(Faalreden.vanStatus(RVO, 403), Faalreden.vanStatus(RVO, 403, "   "))
    }

    @Test
    fun `elke faalmodus benoemt zijn eigen vervolgstap`() {
        // De woorden waar de bediener op afgaat. Elk hoort bij één faalmodus: staat "storing" ook
        // in de weigering, dan zet de melding hem alsnog bij de verkeerde knop.
        assertTrue(Faalreden.onbereikbaar(RVO).contains("storing"))
        assertTrue(Faalreden.vanStatus(RVO, 403).contains("profielservice"))
        assertTrue(Faalreden.vanStatus(RVO, 400).contains("inhoud"))
        assertTrue(Faalreden.geenMagazijn(RVO).contains("ingericht"))

        assertTrue(alleFaalmodi().count { it.contains("storing") } == 1, "meer dan één zin noemt een storing")
    }

    @Test
    fun `een onverwachte fout noemt het type en niet de fouttekst`() {
        // De foutmelding van een library kan van alles bevatten en gaat hier een scherm op; het
        // type zegt genoeg om in het log verder te zoeken.
        val reden = Faalreden.onverwacht(RVO, IllegalStateException("kapotte payload van ontvanger 999993653"))

        assertTrue(reden.contains("IllegalStateException"), reden)
        assertTrue(!reden.contains("999993653"), reden)
    }

    @Test
    fun `een onverwachte status komt met status en al mee`() {
        // Anders blijft er van een 500 of een 404 op het aanleverpad niets over om op te zoeken.
        assertTrue(Faalreden.vanStatus(RVO, 500).contains("500"))
        assertNotEquals(Faalreden.vanStatus(RVO, 500), Faalreden.vanStatus(RVO, 404))
    }

    @ParameterizedTest
    @MethodSource("faalmodiVoor")
    fun `elke reden noemt de organisatie waar het misging`(reden: String) {
        assertTrue(reden.contains(BELASTINGDIENST), "reden zonder organisatie-OIN: $reden")
    }

    @Test
    fun `zonder mislukkingen is er geen regel`() {
        assertNull(Faalreden.samenvatting(emptyList()))
    }

    @Test
    fun `honderd keer dezelfde reden blijft één regel zonder aantallen`() {
        // De tellers ernaast zeggen al hoeveel er misgingen; dat hier herhalen maakt de regel langer
        // zonder dat er iets bij komt.
        val reden = Faalreden.vanStatus(RVO, 403)

        assertEquals("Reden: $reden.", Faalreden.samenvatting(List(100) { reden }))
    }

    @Test
    fun `bij meerdere redenen wint de meest voorkomende, met het aantal soorten erbij`() {
        // Zonder dat aantal leest "97 van de 100" als de hele verklaring, en blijven de drie
        // berichten met een andere oorzaak na de herstelpoging opnieuw liggen.
        val weigering = Faalreden.vanStatus(RVO, 403)
        val redenen = List(97) { weigering } + List(3) { Faalreden.onbereikbaar(BELASTINGDIENST) }

        assertEquals("Meest voorkomende van 2 redenen (97 van de 100): $weigering.", Faalreden.samenvatting(redenen))
    }

    @Test
    fun `bij gelijke stand wint wat het eerst misging, en heet het niet meest voorkomend`() {
        // Zonder vaste keuze zou dezelfde ronde de ene keer de storing en de andere keer de
        // weigering melden, en dan is de melding geen aanknopingspunt meer.
        val eerst = Faalreden.onbereikbaar(RVO)
        val redenen = listOf(eerst, Faalreden.vanStatus(BELASTINGDIENST, 403))

        assertEquals("Eerste van 2 redenen (1 van de 2): $eerst.", Faalreden.samenvatting(redenen))
    }

    @Test
    fun `honderd verschillende redenen blijven één leesbare regel`() {
        // De grens uit het acceptatiecriterium ligt bij het aantal berichten, niet bij het aantal
        // oorzaken: honderd magazijnen die elk één keer wegvallen mag geen honderd regels geven.
        val redenen = (1..100).map { Faalreden.onbereikbaar("0000000000000010%04d".format(it)) }

        val samenvatting = Faalreden.samenvatting(redenen)!!

        assertTrue(!samenvatting.contains("\n"), "de samenvatting hoort één regel te zijn: $samenvatting")
        assertTrue(samenvatting.startsWith("Eerste van 100 redenen (1 van de 100): "), samenvatting)
    }

    private fun alleFaalmodi() = faalmodiVan(RVO)

    private companion object {

        const val RVO = "00000000000000100000"
        const val BELASTINGDIENST = "00000001823288444000"

        fun faalmodiVan(oin: String) = listOf(
            Faalreden.geenMagazijn(oin),
            Faalreden.onbereikbaar(oin),
            Faalreden.onverwacht(oin, IllegalStateException("stuk")),
            Faalreden.vanStatus(oin, 403),
            Faalreden.vanStatus(oin, 400),
            Faalreden.vanStatus(oin, 500),
        )

        @JvmStatic
        fun faalmodiVoor() = faalmodiVan(BELASTINGDIENST)
    }
}
