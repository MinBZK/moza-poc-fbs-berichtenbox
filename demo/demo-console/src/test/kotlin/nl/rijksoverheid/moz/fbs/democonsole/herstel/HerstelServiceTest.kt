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
import nl.rijksoverheid.moz.fbs.democonsole.storing.StoringService
import nl.rijksoverheid.moz.fbs.democonsole.tempo.TempoService
import nl.rijksoverheid.moz.fbs.democonsole.tempo.TempoStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HerstelServiceTest {

    private val tempoService = mockk<TempoService>()
    private val storingService = mockk<StoringService>()
    private val magazijnDatabase = mockk<MagazijnDatabase>()
    private val basisdataset = mockk<Basisdataset>()
    private val aanleverService = mockk<AanleverService>()

    private val service = HerstelService(tempoService, storingService, magazijnDatabase, basisdataset, aanleverService)

    private fun alleStappenSlagen() {
        every { tempoService.stop() } returns TempoStatus(false, 0, 0)
        every { storingService.reset() } just Runs
        every { magazijnDatabase.leegAlles() } returns mapOf("magazijn-a" to 20, "magazijn-b" to 20)
        every { basisdataset.laad() } returns emptyList()
        every { aanleverService.leverAan(any()) } returns AanleverResultaat(40, 40, 0, 0)
    }

    @Test
    fun `herstel doorloopt de stappen in de juiste volgorde`() {
        // De volgorde draagt betekenis: een lopende stroom zou tijdens het legen blijven vullen,
        // en storingen zouden de basisvulling laten mislukken.
        alleStappenSlagen()

        service.herstel()

        verifyOrder {
            tempoService.stop()
            storingService.reset()
            magazijnDatabase.leegAlles()
            aanleverService.leverAan(any())
        }
    }

    @Test
    fun `herstel rapporteert wat er geleegd en gevuld is`() {
        alleStappenSlagen()

        val resultaat = service.herstel()

        assertEquals(mapOf("magazijn-a" to 20, "magazijn-b" to 20), resultaat.geleegd)
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
}
