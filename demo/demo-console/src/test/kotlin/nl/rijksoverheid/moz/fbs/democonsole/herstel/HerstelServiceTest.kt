package nl.rijksoverheid.moz.fbs.democonsole.herstel

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase
import nl.rijksoverheid.moz.fbs.democonsole.omgeving.OmgevingConfig
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
    private val omgeving = mockk<OmgevingConfig>()

    private val service = HerstelService(
        tempoService,
        storingService,
        magazijnDatabase,
        basisdataset,
        aanleverService,
        simulatorService,
        omgeving,
    )

    private fun alleStappenSlagen() {
        every { omgeving.simulator() } returns true
        every { tempoService.stop() } returns TempoStatus(false, 0, 0)
        every { storingService.reset() } just Runs
        every { magazijnDatabase.leegAlles() } returns mapOf("magazijn-a" to 20, "magazijn-b" to 20)
        every { basisdataset.laad() } returns emptyList()
        every { aanleverService.leverAan(any()) } returns AanleverResultaat(40, 40, 0, 0)
        every { simulatorService.herstel() } returns mapOf("berichten" to 2000, "magazijnen" to 98)
        every { simulatorService.vulStandaard() } returns
            nl.rijksoverheid.moz.fbs.democonsole.simulator.SeedUitkomst(98, 4, 7840, 1960, 0, 500)
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
            simulatorService.herstel()
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
        assertEquals(mapOf("berichten" to 2000, "magazijnen" to 98), resultaat.gesimuleerd)
        // Herstel belooft "terug naar vlak na de eerste basisvulling"; dan horen de gesimuleerde
        // magazijnen ook weer gevuld te zijn, anders staat de fan-out-demo op nul berichten.
        assertEquals(7840, resultaat.gesimuleerdGevuld)
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
    fun `zonder simulator worden de echte magazijnen gewoon geleegd en gevuld`() {
        // Het paneel verbergt de simulator-knoppen op zo'n omgeving, maar de knop Herstel demo
        // draagt die markering niet. Riep hij de simulator toch aan, dan bleef de omgeving achter
        // met de stroom gestopt en de storingen weg, maar met de berichten van de vorige demo er
        // nog in.
        alleStappenSlagen()
        every { omgeving.simulator() } returns false

        val resultaat = service.herstel()

        verify { magazijnDatabase.leegAlles() }
        verify { aanleverService.leverAan(any()) }
        verify(exactly = 0) { simulatorService.herstel() }
        verify(exactly = 0) { simulatorService.vulStandaard() }
        assertEquals("deze omgeving kent geen magazijn-simulator", resultaat.gesimuleerdOvergeslagen)
    }

    @Test
    fun `een onbereikbare simulator laat het herstel staan en meldt waarom hij is overgeslagen`() {
        // Het echte werk is dan al gedaan; dat als mislukt melden laat de bediener nog een keer
        // legen en vullen. De reden hoort wél in het antwoord, anders is dit een stille fout.
        alleStappenSlagen()
        every { simulatorService.herstel() } throws IllegalStateException("simulator onbereikbaar")

        val resultaat = service.herstel()

        assertEquals(mapOf("magazijn-a" to 20, "magazijn-b" to 20), resultaat.geleegd)
        assertEquals(40, resultaat.vulling.geslaagd)
        assertEquals("simulator onbereikbaar", resultaat.gesimuleerdOvergeslagen)
        assertEquals(emptyMap<String, Int>(), resultaat.gesimuleerd)
        assertEquals(0, resultaat.gesimuleerdGevuld)
    }

    @Test
    fun `een simulator die pas bij het vullen struikelt meldt dat net zo goed`() {
        // Het legen van de gesimuleerde magazijnen was dan al gelukt; zonder deze melding staat de
        // fan-out-demo op nul berichten terwijl de knop groen werd.
        alleStappenSlagen()
        every { simulatorService.vulStandaard() } throws IllegalStateException("seed afgebroken")

        val resultaat = service.herstel()

        assertEquals("seed afgebroken", resultaat.gesimuleerdOvergeslagen)
        assertEquals(0, resultaat.gesimuleerdGevuld)
    }

    @Test
    fun `een fout zonder message valt terug op de naam in plaats van stil te verdwijnen`() {
        alleStappenSlagen()
        every { simulatorService.herstel() } throws IllegalStateException()

        assertEquals("IllegalStateException", service.herstel().gesimuleerdOvergeslagen)
    }

    @Test
    fun `een geslaagd herstel meldt niets als overgeslagen`() {
        alleStappenSlagen()

        assertNull(service.herstel().gesimuleerdOvergeslagen)
    }
}
