package nl.rijksoverheid.moz.fbs.democonsole.herstel

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import nl.rijksoverheid.moz.fbs.democonsole.HERSTELTIJD_MELDING
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.Faalreden
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase
import nl.rijksoverheid.moz.fbs.democonsole.simulator.GesimuleerdHerstel
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorService
import nl.rijksoverheid.moz.fbs.democonsole.storing.StoringService
import nl.rijksoverheid.moz.fbs.democonsole.tempo.TempoService
import nl.rijksoverheid.moz.fbs.democonsole.tempo.TempoStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HerstelServiceTest {

    private val tempoService = mockk<TempoService>()
    private val storingService = mockk<StoringService>()
    private val magazijnDatabase = mockk<MagazijnDatabase>()
    private val basisdataset = mockk<Basisdataset>()
    private val aanleverService = mockk<AanleverService>()
    private val simulatorService = mockk<SimulatorService>()

    private val service = HerstelService(
        tempoService,
        storingService,
        magazijnDatabase,
        basisdataset,
        aanleverService,
        simulatorService,
    )

    private fun alleStappenSlagen() {
        every { tempoService.stop() } returns TempoStatus(false, 0, 0)
        every { storingService.reset() } just Runs
        every { magazijnDatabase.leegAlles() } returns mapOf("magazijn-a" to 20, "magazijn-b" to 20)
        every { basisdataset.laad() } returns emptyList()
        every { aanleverService.leverAan(any()) } returns AanleverResultaat.van(40, 40, 0, emptyList())
        every { simulatorService.herstelZoMogelijk() } returns GesimuleerdHerstel(berichten = 2000, magazijnen = 98)
        every { simulatorService.vulStandaard() } returns
            nl.rijksoverheid.moz.fbs.democonsole.simulator.SeedUitkomst(98, 4, 10584, 2646, 0, 500)
    }

    @Test
    fun `herstel doorloopt de stappen in de juiste volgorde`() {
        // De volgorde draagt betekenis: een lopende stroom zou tijdens het legen blijven vullen,
        // en storingen zouden de basisvulling laten mislukken. De gesimuleerde magazijnen komen als
        // laatste, want zij houden de twee echte magazijnen nergens voor tegen — andersom liet een
        // onbereikbare simulator ze ongemoeid en bleef de omgeving halverwege staan.
        alleStappenSlagen()

        service.herstel()

        verifyOrder {
            tempoService.stop()
            storingService.reset()
            magazijnDatabase.leegAlles()
            aanleverService.leverAan(any())
            simulatorService.herstelZoMogelijk()
            simulatorService.vulStandaard()
        }
    }

    @Test
    fun `herstel rapporteert wat er geleegd en gevuld is`() {
        alleStappenSlagen()

        val resultaat = service.herstel()

        assertEquals(mapOf("magazijn-a" to 20, "magazijn-b" to 20), resultaat.geleegd)
        // De gesimuleerde magazijnen horen er net zo goed bij: zonder dat toont de demo na een
        // herstel nog steeds honderd gevulde organisaties.
        assertEquals(GesimuleerdHerstel(berichten = 2000, magazijnen = 98), resultaat.gesimuleerd)
        // Herstel belooft "terug naar vlak na de eerste basisvulling"; dan horen de gesimuleerde
        // magazijnen ook weer gevuld te zijn, anders staat de fan-out-demo op nul berichten.
        assertEquals(10584, resultaat.gesimuleerdGevuld)
        assertEquals(40, resultaat.vulling.geslaagd)
    }

    @Test
    fun `een falende stap breekt af met de fout in plaats van stil door te gaan`() {
        // Half herstellen is erger dan niet herstellen: de bediener denkt dan dat de omgeving
        // schoon is terwijl er storingen aan staan.
        alleStappenSlagen()
        every { storingService.reset() } throws IllegalStateException("Toxiproxy onbereikbaar")

        assertThrows(IllegalStateException::class.java) { service.herstel() }

        verify(exactly = 0) { magazijnDatabase.leegAlles() }
        verify(exactly = 0) { aanleverService.leverAan(any()) }
    }

    @Test
    fun `een overgeslagen simulator laat het herstel staan en reist mee naar het paneel`() {
        // Het echte werk is dan al gedaan; dat als mislukt melden laat de bediener nog een keer
        // legen en vullen. De reden hoort wél in het antwoord, anders is dit een stille fout.
        alleStappenSlagen()
        every { simulatorService.herstelZoMogelijk() } returns
            GesimuleerdHerstel(overgeslagen = "deze omgeving kent geen magazijn-simulator")

        val resultaat = service.herstel()

        assertEquals(mapOf("magazijn-a" to 20, "magazijn-b" to 20), resultaat.geleegd)
        assertEquals(40, resultaat.vulling.geslaagd)
        assertEquals("deze omgeving kent geen magazijn-simulator", resultaat.gesimuleerd.overgeslagen)
        assertEquals(0, resultaat.gesimuleerdGevuld)
        verify(exactly = 0) { simulatorService.vulStandaard() }
    }

    @Test
    fun `een simulator die pas bij het vullen struikelt meldt dat als half gedaan`() {
        // Het legen van de gesimuleerde magazijnen was dan al gelukt en is niet terug te draaien.
        // Zonder deze melding staat de fan-out-demo op nul berichten terwijl de knop groen werd.
        alleStappenSlagen()
        every { simulatorService.vulStandaard() } throws IllegalStateException("seed afgebroken")

        val resultaat = service.herstel()

        assertEquals("wel geleegd, niet gevuld: seed afgebroken", resultaat.gesimuleerd.overgeslagen)
        assertEquals(2000, resultaat.gesimuleerd.berichten)
        assertEquals(0, resultaat.gesimuleerdGevuld)
    }

    @Test
    fun `een fout zonder message valt terug op de naam in plaats van stil te verdwijnen`() {
        alleStappenSlagen()
        every { simulatorService.vulStandaard() } throws IllegalStateException()

        assertEquals("wel geleegd, niet gevuld: IllegalStateException", service.herstel().gesimuleerd.overgeslagen)
    }

    @Test
    fun `een geslaagd herstel meldt niets als overgeslagen`() {
        alleStappenSlagen()

        assertNull(service.herstel().gesimuleerd.overgeslagen)
    }

    @Test
    fun `een herstel zonder mislukkingen meldt alleen de hersteltijd`() {
        alleStappenSlagen()

        assertEquals(HERSTELTIJD_MELDING, service.herstel().letOp)
    }

    @Test
    fun `een vulling die niet aankwam draagt haar reden mee naar het paneel`() {
        alleStappenSlagen()

        val mislukt = AanleverResultaat.van(40, 0, 0, List(40) { Faalreden.onbereikbaar("00000000000000100000") })

        every { aanleverService.leverAan(any()) } returns mislukt

        // De volledige regel, want juist de naad tussen de twee zinnen is wat hier kan misgaan.
        assertEquals("${mislukt.letOp} $HERSTELTIJD_MELDING", service.herstel().letOp)
    }

    @Test
    fun `een mislukte basisvulling breekt af nadat de magazijnen al geleegd zijn`() {
        // Vastgelegd omdat het niet vanzelf spreekt: hier is het legen onomkeerbaar gebeurd en gaat
        // de rest niet door. De simulator wordt dan bewust niet meer aangeraakt — die opnieuw
        // vullen tegen lege echte magazijnen maakt de tussenstand alleen verwarrender.
        alleStappenSlagen()
        every { aanleverService.leverAan(any()) } throws IllegalStateException("magazijn weigert")

        assertThrows(IllegalStateException::class.java) { service.herstel() }

        verify { magazijnDatabase.leegAlles() }
        verify(exactly = 0) { simulatorService.herstelZoMogelijk() }
        verify(exactly = 0) { simulatorService.vulStandaard() }
    }
}
