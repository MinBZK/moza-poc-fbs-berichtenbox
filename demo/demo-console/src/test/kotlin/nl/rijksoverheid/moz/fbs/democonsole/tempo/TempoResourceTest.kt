package nl.rijksoverheid.moz.fbs.democonsole.tempo

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.BadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Het adres van de stroom, los van de stroom zelf.
 *
 * `TempoService` toetst hetzelfde interval nog eens, dus een test over HTTP kan niet zien wie de
 * weigering gaf: haal je de grens uit de resource weg, dan blijft alles groen omdat de service hem
 * alsnog vangt. Met een dubbel voor de service is dat wél zichtbaar — en dat is precies wat de
 * KDoc van [TempoResource.start] belooft: het adres weigert vóór de stroom iets te doen krijgt.
 */
class TempoResourceTest {

    private val service = mockk<TempoService>()

    private val resource = TempoResource(service)

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "3601", "abc", "1.5", "3000000000"])
    fun `een onbruikbaar interval bereikt de stroom niet`(interval: String) {
        // De service is gestubd hoewel hij niet aangeroepen hoort te worden: zonder stub struikelt
        // een doorgelaten waarde over een ongestubde mock, en dan zegt de faalmelding niets over de
        // weigering die uitbleef.
        every { service.start(any()) } returns TempoStatus(true, 0, 0)

        val fout = assertThrows<BadRequestException> { resource.start(interval) }

        assertTrue(fout.message!!.contains("interval"), "de melding hoort het interval-veld te noemen")
        verify(exactly = 0) { service.start(any()) }
    }

    @Test
    fun `de melding noemt de eenheid van het interval`() {
        // Zonder eenheid leest "tussen 1 en 3600" net zo goed als milliseconden. De melding van
        // TempoService noemde seconden; die is via dit adres niet meer te bereiken.
        every { service.start(any()) } returns TempoStatus(true, 0, 0)

        val fout = assertThrows<BadRequestException> { resource.start("3601") }

        assertTrue(fout.message!!.contains("seconden"), "de eenheid ontbreekt: ${fout.message}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["1", "3600"])
    fun `een interval op de grens komt ongewijzigd bij de stroom aan`(interval: String) {
        every { service.start(any()) } returns TempoStatus(true, interval.toInt(), 0)

        assertEquals(interval.toInt(), resource.start(interval).intervalSeconden)

        verify(exactly = 1) { service.start(interval.toInt()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `een leeg interval valt terug op de standaard van het invoerveld`(interval: String) {
        every { service.start(any()) } returns TempoStatus(true, TempoResource.STANDAARD_INTERVAL, 0)

        resource.start(interval)

        verify(exactly = 1) { service.start(TempoResource.STANDAARD_INTERVAL) }
    }
}
