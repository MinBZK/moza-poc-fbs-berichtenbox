package nl.rijksoverheid.moz.fbs.democonsole.simulator

import io.mockk.every
import io.mockk.mockk
import nl.rijksoverheid.moz.fbs.democonsole.omgeving.OmgevingConfig
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * De bediening van de gesimuleerde magazijnen.
 *
 * Twee dingen worden hier vastgepind. Dat "zet er k van de n aan" in **één** aanroep gaat — bij
 * honderd magazijnen zou een knop die honderd verzoeken doet trager zijn dan de demo die hij moet
 * ondersteunen. En dat het aantal van de simulator zelf komt: een eigen instelling ernaast zou uit
 * de pas kunnen lopen, en dan stuurt de console magazijnen aan die er niet zijn of slaat het er een
 * paar over.
 */
class SimulatorServiceTest {

    private val beheer = mockk<SimulatorBeheerClient>()
    private val omgeving = mockk<OmgevingConfig> { every { simulator() } returns true }
    private val service = SimulatorService(beheer, omgeving)

    @Test
    fun `de eerste k blijven normaal en de rest gaat op storing`() {
        gegevenMagazijnen(5)

        val verzoek = slot<BulkGedragVerzoek>()

        every { beheer.zetGedrag(capture(verzoek)) } returns BulkGedragUitkomst(5, emptyList())

        assertEquals(SimulatorStand(actief = 3, totaal = 5), service.zetActief(3))

        assertEquals(
            listOf("NORMAAL", "NORMAAL", "NORMAAL", "STUK", "STUK"),
            verzoek.captured.aanpassingen.map { it.modus },
        )
    }

    /** Eén aanroep, niet n losse; dat is de hele reden dat het beheerpad een bulk-vorm heeft. */
    @Test
    fun `alle magazijnen gaan in één aanroep mee`() {
        gegevenMagazijnen(100)

        every { beheer.zetGedrag(any()) } returns BulkGedragUitkomst(100, emptyList())

        service.zetActief(2)

        verify(exactly = 1) { beheer.zetGedrag(any()) }
    }

    @Test
    fun `nul actief zet alles op storing en alles actief zet niets op storing`() {
        gegevenMagazijnen(4)

        val verzoek = slot<BulkGedragVerzoek>()

        every { beheer.zetGedrag(capture(verzoek)) } returns BulkGedragUitkomst(4, emptyList())

        service.zetActief(0)

        assertEquals(4, verzoek.captured.aanpassingen.count { it.modus == "STUK" })

        service.zetActief(4)

        assertEquals(4, verzoek.captured.aanpassingen.count { it.modus == "NORMAAL" })
    }

    @Test
    fun `een aantal buiten het bereik is een fout en geen stille aanpassing`() {
        gegevenMagazijnen(3)

        assertThrows<IllegalArgumentException> { service.zetActief(4) }
        assertThrows<IllegalArgumentException> { service.zetActief(-1) }
    }

    /**
     * Loopt de console uit de pas met wat de simulator kent, dan hoort dat op te vallen. Stil
     * doorgaan zou betekenen dat een deel van de magazijnen niet meedoet met een storing die de
     * verteller wél heeft aangezet.
     */
    @Test
    fun `onbekende magazijnen worden gemeld en niet genegeerd`() {
        gegevenMagazijnen(2)

        every { beheer.zetGedrag(any()) } returns BulkGedragUitkomst(1, listOf("00000009000000000002"))

        val fout = assertThrows<IllegalStateException> { service.zetActief(1) }

        assertEquals(true, fout.message?.contains("00000009000000000002"))
    }

    @Test
    fun `de magazijnen komen op OIN gesorteerd terug`() {
        every { beheer.magazijnen() } returns listOf(
            SimulatorMagazijn("00000009000000000003", "Derde", "NORMAAL"),
            SimulatorMagazijn("00000009000000000001", "Eerste", "NORMAAL"),
            SimulatorMagazijn("00000009000000000002", "Tweede", "TRAAG"),
        )

        assertEquals(listOf("Eerste", "Tweede", "Derde"), service.magazijnen().map { it.naam })
    }

    @Test
    fun `herstellen meldt hoeveel er is opgeruimd`() {
        every { beheer.legen() } returns LeegUitkomst(berichten = 2000, magazijnenTeruggezet = 98)

        assertEquals(GesimuleerdHerstel(berichten = 2000, magazijnen = 98), service.herstel())
    }

    @Test
    fun `vullen geeft de opgegeven ondernemers en aantallen door`() {
        val verzoek = slot<SeedVerzoek>()

        every { beheer.seed(capture(verzoek)) } returns SeedUitkomst(98, 2, 3920, 980, 0, 500)

        service.vul(listOf("KVK:12345678", "BSN:999993653"), berichtenPerMagazijn = 20, bijlageElke = 4)

        assertEquals(listOf("KVK:12345678", "BSN:999993653"), verzoek.captured.ontvangers)
        assertEquals(20, verzoek.captured.berichtenPerMagazijn)
        assertEquals(4, verzoek.captured.bijlageElke)
    }

    /** De standaardvulling gebruikt dezelfde vier ondernemers als de knop en het herstel. */
    @Test
    fun `de standaardvulling zet twintig berichten klaar voor alle vier de ondernemers`() {
        val verzoek = slot<SeedVerzoek>()

        every { beheer.seed(capture(verzoek)) } returns SeedUitkomst(98, 4, 7840, 1960, 0, 500)

        service.vulStandaard()

        assertEquals(SimulatorService.ONDERNEMERS, verzoek.captured.ontvangers)
        assertEquals(20, verzoek.captured.berichtenPerMagazijn)
    }

    private fun gegevenMagazijnen(aantal: Int) {
        every { beheer.magazijnen() } returns (1..aantal).map {
            SimulatorMagazijn("0000000900000000%04d".format(it), "Magazijn $it", "NORMAAL")
        }
    }

    @Test
    fun `zonder simulator wordt het terugzetten overgeslagen met de reden erbij`() {
        // Het paneel verbergt de simulator-knoppen op zo'n omgeving, maar Herstel demo en Magazijnen
        // legen raken hem als deelstap. Die zouden anders gegarandeerd falen zonder iets te legen.
        every { omgeving.simulator() } returns false

        assertEquals(
            GesimuleerdHerstel(overgeslagen = "deze omgeving kent geen magazijn-simulator"),
            service.herstelZoMogelijk(),
        )

        verify(exactly = 0) { beheer.legen() }
    }

    @Test
    fun `een onbereikbare simulator wordt een reden en geen fout`() {
        every { beheer.legen() } throws IllegalStateException("simulator onbereikbaar")

        assertEquals(GesimuleerdHerstel(overgeslagen = "simulator onbereikbaar"), service.herstelZoMogelijk())
    }

    @Test
    fun `een fout zonder message valt terug op de naam in plaats van stil te verdwijnen`() {
        every { beheer.legen() } throws IllegalStateException()

        assertEquals(GesimuleerdHerstel(overgeslagen = "IllegalStateException"), service.herstelZoMogelijk())
    }

    @Test
    fun `de knop die expliciet op de simulator mikt, krijgt de fout wel te zien`() {
        // Anders meldt hij "0 berichten weg" terwijl de simulator niets deed — en juist die knop is
        // aangeklikt omdat iemand de simulator wilde raken.
        every { beheer.legen() } throws IllegalStateException("simulator onbereikbaar")

        assertThrows<IllegalStateException> { service.herstel() }
    }

    @Test
    fun `een geslaagd terugzetten draagt geen reden`() {
        every { beheer.legen() } returns LeegUitkomst(berichten = 2000, magazijnenTeruggezet = 98)

        assertEquals(GesimuleerdHerstel(berichten = 2000, magazijnen = 98), service.herstelZoMogelijk())
    }
}
