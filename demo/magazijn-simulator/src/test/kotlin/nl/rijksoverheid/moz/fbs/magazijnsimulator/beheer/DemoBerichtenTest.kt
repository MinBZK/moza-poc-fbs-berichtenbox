package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Identificatie
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.IdentificatieType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

/**
 * Wat een demo laat zien is afgeleid, en juist daarom te toetsen zonder database.
 *
 * De twee eigenschappen die hier vastliggen zijn wat een berichtenbox geloofwaardig maakt: een lijst
 * loopt van nieuw naar oud, en de onderwerpen verschillen. Draait de volgorde om, of krijgt elk
 * bericht hetzelfde onderwerp, dan is de illusie weg zonder dat er iets stukgaat.
 */
class DemoBerichtenTest {

    @Test
    fun `het eerste bericht is het oudste, zodat de lijst van nieuw naar oud loopt`() {
        val berichten = DemoBerichten.voor(MAGAZIJN, ONTVANGER, aantal = 8, bijlageElke = 0, nu = NU)

        val tijdstippen = berichten.map { it.bericht.tijdstipOntvangst }

        assertEquals(tijdstippen.sorted(), tijdstippen, "de tijdstippen horen op te lopen")
        assertTrue(tijdstippen.first() < tijdstippen.last(), "acht berichten horen niet op hetzelfde moment")
    }

    @Test
    fun `de onderwerpen wisselen af in plaats van acht keer hetzelfde te zijn`() {
        val onderwerpen = DemoBerichten.voor(MAGAZIJN, ONTVANGER, aantal = 8, bijlageElke = 0, nu = NU)
            .map { it.bericht.onderwerp }

        assertTrue(onderwerpen.distinct().size >= 5, "verwacht variatie, kreeg $onderwerpen")
    }

    /** Leeg, één en meerdere: bij precies één bericht is een omgekeerde volgorde onzichtbaar. */
    @ParameterizedTest
    @ValueSource(ints = [0, 1, 3])
    fun `de gevraagde hoeveelheid komt eruit`(aantal: Int) {
        assertEquals(aantal, DemoBerichten.voor(MAGAZIJN, ONTVANGER, aantal, bijlageElke = 0, nu = NU).size)
    }

    @Test
    fun `hetzelfde verzoek levert dezelfde bericht-ids op`() {
        val eerste = DemoBerichten.voor(MAGAZIJN, ONTVANGER, aantal = 5, bijlageElke = 0, nu = NU)
        val tweede = DemoBerichten.voor(MAGAZIJN, ONTVANGER, aantal = 5, bijlageElke = 0, nu = NU)

        assertEquals(eerste.map { it.bericht.berichtId }, tweede.map { it.bericht.berichtId })
    }

    private companion object {
        const val MAGAZIJN = "00000009000000000001"

        val ONTVANGER = Identificatie(IdentificatieType.KVK, "90000001")

        val NU: Instant = Instant.parse("2026-09-02T10:00:00Z")
    }
}
