package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
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
     * Elke zin letterlijk, want dit is wat er op het scherm komt. Een reden die zelf op een leesteken
     * eindigde kreeg er een punt achter geplakt ("...storing aan?."); asserties die hun verwachting
     * met dezelfde productiefunctie opbouwen, zagen dat niet.
     */
    @ParameterizedTest
    @MethodSource("regels")
    fun `de regel staat er letterlijk zo`(verwacht: String, reden: String) {
        assertEquals(verwacht, Faalreden.samenvatting(listOf(reden)))
    }

    @Test
    fun `een storing op de profielservice leest niet als een afwijzing van het bericht`() {
        // Het magazijn geeft bij een 503 zelf een detail mee ("probeer over 30 seconden opnieuw").
        // Dat overnemen zou "wees het bericht af" opleveren terwijl er niets is afgewezen en er
        // juist een storing aanstaat — de knop waar de bediener dan naartoe moet.
        val reden = Faalreden.vanStatus(RVO, 503, "De toestemmingscontrole kon niet uitgevoerd worden.")

        assertEquals("magazijn $RVO kon het bericht niet verwerken (HTTP 503); mogelijk staat er nog een storing aan", reden)
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
        // De woorden waar de bediener op afgaat.
        assertTrue(Faalreden.onbereikbaar(RVO).contains("storing"))
        assertTrue(Faalreden.vanStatus(RVO, 403).contains("profielservice"))
        assertTrue(Faalreden.vanStatus(RVO, 400).contains("inhoud"))
        assertTrue(Faalreden.geenMagazijn(RVO).contains("ingericht"))

        // Naar de storingsknop wijzen alleen de twee faalmodi die een storing kán veroorzaken:
        // een magazijn dat niet antwoordt, en een magazijn dat zelf struikelt. Zou de weigering of
        // de afkeuring dat woord ook dragen, dan stuurt de melding de bediener naar de verkeerde
        // knop terwijl er niets stuk is.
        listOf(Faalreden.vanStatus(RVO, 403), Faalreden.vanStatus(RVO, 400), Faalreden.geenMagazijn(RVO))
            .forEach { assertTrue(!it.contains("storing"), "deze reden wijst ten onrechte naar een storing: $it") }
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

    @Test
    fun `een lange reden van het magazijn blijft binnen één regel`() {
        // De lay-out van de melding ligt niet bij het magazijn: het acceptatiecriterium is één
        // leesbare regel, ook als de keten er een lap tekst met regeleindes in zet.
        val reden = Faalreden.vanStatus(RVO, 400, "eerste regel\n\ntweede regel " + "x".repeat(500))

        assertTrue(!reden.contains("\n"), "de reden hoort op één regel te passen: $reden")
        assertTrue(reden.length < 300, "de reden is ${reden.length} tekens lang")
        assertTrue(reden.contains("eerste regel tweede regel"), reden)

        // En door de samenvatting heen, want daar zit de naad: een afgekapte reden eindigt op een
        // beletselteken en kreeg daar eerder alsnog een punt achter geplakt.
        assertTrue(Faalreden.samenvatting(listOf(reden))!!.endsWith("…"), Faalreden.samenvatting(listOf(reden))!!)
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
        val redenen = List(97) { Faalreden.vanStatus(RVO, 400) } + List(3) { Faalreden.onbereikbaar(RVO) }

        assertEquals(
            "Meest voorkomende van 2 redenen (97 van de 100): magazijn $RVO keurde het bericht af op de inhoud.",
            Faalreden.samenvatting(redenen),
        )
    }

    @Test
    fun `bij gelijke stand wint wat het eerst misging, en heet het niet meest voorkomend`() {
        // Zonder vaste keuze zou dezelfde ronde de ene keer de storing en de andere keer de
        // weigering melden, en dan is de melding geen aanknopingspunt meer.
        val redenen = listOf(Faalreden.onbereikbaar(RVO), Faalreden.vanStatus(BELASTINGDIENST, 403))

        assertEquals(
            "Eerste van 2 redenen (1 van de 2): magazijn $RVO was niet bereikbaar; " +
                "mogelijk staat er nog een storing aan.",
            Faalreden.samenvatting(redenen),
        )
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

        /** Elke zin zoals de bediener hem leest, naast de faalmodus die hem oplevert. */
        @JvmStatic
        fun regels() = listOf(
            arguments(
                "Reden: voor organisatie $RVO is in deze omgeving geen magazijn-adres ingericht.",
                Faalreden.geenMagazijn(RVO),
            ),
            arguments(
                "Reden: magazijn $RVO was niet bereikbaar; mogelijk staat er nog een storing aan.",
                Faalreden.onbereikbaar(RVO),
            ),
            arguments(
                "Reden: aanleveren bij magazijn $RVO brak onverwacht af (IllegalStateException).",
                Faalreden.onverwacht(RVO, IllegalStateException("stuk")),
            ),
            arguments(
                "Reden: organisatie $RVO weigert het bericht; begin bij de voorkeuren van deze " +
                    "ondernemer in de profielservice.",
                Faalreden.vanStatus(RVO, 403),
            ),
            arguments(
                "Reden: magazijn $RVO keurde het bericht af op de inhoud.",
                Faalreden.vanStatus(RVO, 400),
            ),
            arguments(
                "Reden: magazijn $RVO antwoordde met HTTP 404.",
                Faalreden.vanStatus(RVO, 404),
            ),
            arguments(
                "Reden: magazijn $RVO kon het bericht niet verwerken (HTTP 500); mogelijk staat er " +
                    "nog een storing aan.",
                Faalreden.vanStatus(RVO, 500),
            ),
        )
    }
}
