package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * De reden onder de tellers. Wat hier verkeerd gaat, gaat verkeerd op het moment dat er iemand
 * meekijkt: dan staat er "1 mislukt" met een zin die de bediener naar de verkeerde knop stuurt.
 */
class FaalredenTest {

    @Test
    fun `de vier faalmodi zijn van elkaar te onderscheiden`() {
        // De hele reden van bestaan: een storing vraagt om een andere handeling dan een ontvanger
        // die niet geregistreerd staat, en die weer om een andere dan een half ingerichte omgeving.
        val redenen = listOf(
            Faalreden.geenMagazijn(RVO),
            Faalreden.onbereikbaar(RVO),
            Faalreden.vanStatus(RVO, 403),
            Faalreden.vanStatus(RVO, 400),
        )

        assertEquals(redenen.size, redenen.toSet().size, "faalmodi met dezelfde zin: $redenen")
    }

    @Test
    fun `een weigering noemt de deelnemersregistratie en een onbereikbaar magazijn de storing`() {
        assertTrue(Faalreden.vanStatus(RVO, 403).contains("geregistreerd"))
        assertTrue(Faalreden.onbereikbaar(RVO).contains("storing"))
    }

    @Test
    fun `een onverwachte status komt met status en al mee`() {
        // Anders blijft er van een 500 of een 404 op het aanleverpad niets over om op te zoeken.
        assertTrue(Faalreden.vanStatus(RVO, 500).contains("500"))
        assertNotEquals(Faalreden.vanStatus(RVO, 500), Faalreden.vanStatus(RVO, 404))
    }

    @Test
    fun `elke reden noemt de organisatie waar het misging`() {
        listOf(
            Faalreden.geenMagazijn(BELASTINGDIENST),
            Faalreden.onbereikbaar(BELASTINGDIENST),
            Faalreden.vanStatus(BELASTINGDIENST, 403),
            Faalreden.vanStatus(BELASTINGDIENST, 400),
            Faalreden.vanStatus(BELASTINGDIENST, 500),
        ).forEach { assertTrue(it.contains(BELASTINGDIENST), "reden zonder organisatie-OIN: $it") }
    }

    @Test
    fun `zonder mislukkingen is er geen regel`() {
        assertNull(Faalreden.samenvatting(emptyList()))
    }

    @Test
    fun `één mislukking levert die ene reden op`() {
        assertEquals("Reden: ${Faalreden.onbereikbaar(RVO)}.", Faalreden.samenvatting(listOf(Faalreden.onbereikbaar(RVO))))
    }

    @Test
    fun `honderd keer dezelfde reden blijft één regel zonder aantallen`() {
        // De tellers ernaast zeggen al hoeveel er misgingen; dat hier herhalen maakt de regel langer
        // zonder dat er iets bij komt.
        val reden = Faalreden.vanStatus(RVO, 403)

        assertEquals("Reden: $reden.", Faalreden.samenvatting(List(100) { reden }))
    }

    @Test
    fun `bij meerdere redenen wint de meest voorkomende, met haar aandeel erbij`() {
        val weigering = Faalreden.vanStatus(RVO, 403)
        val redenen = List(97) { weigering } + List(3) { Faalreden.onbereikbaar(BELASTINGDIENST) }

        assertEquals("Meest voorkomende reden (97 van de 100): $weigering.", Faalreden.samenvatting(redenen))
    }

    @Test
    fun `bij gelijke stand wint wat het eerst misging`() {
        // Zonder vaste keuze zou dezelfde ronde de ene keer de storing en de andere keer de
        // weigering melden, en dan is de melding geen aanknopingspunt meer.
        val eerst = Faalreden.onbereikbaar(RVO)
        val redenen = listOf(eerst, Faalreden.vanStatus(BELASTINGDIENST, 403))

        assertEquals("Meest voorkomende reden (1 van de 2): $eerst.", Faalreden.samenvatting(redenen))
    }

    private companion object {

        const val RVO = "00000000000000100000"
        const val BELASTINGDIENST = "00000001823288444000"
    }
}
