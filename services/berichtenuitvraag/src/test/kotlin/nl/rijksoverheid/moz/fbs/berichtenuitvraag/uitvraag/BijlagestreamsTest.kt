package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.WebApplicationException
import nl.rijksoverheid.moz.fbs.common.exception.FbsFoutException
import nl.rijksoverheid.moz.fbs.common.exception.Foutcode
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration

/**
 * Het plafond op gestreamde bijlage-downloads: per pod en per ontvanger, een plek die precies één
 * keer vrijkomt, en een verkeerde instelling die bij het opstarten faalt.
 */
class BijlagestreamsTest {

    private val eerste = Bsn("999990019")
    private val tweede = Bsn("999990032")
    private val derde = Bsn("999990044")

    private fun streams(max: Int = 2, perOntvanger: Int = 1, stilstand: Duration = Duration.ofSeconds(60)) =
        Bijlagestreams(
            object : BijlageConfig {
                override fun maxStilstand(): Duration = stilstand
                override fun maxStreams(): Int = max
                override fun maxStreamsPerOntvanger(): Int = perOntvanger
            },
        )

    private fun teVeel(blok: () -> Unit) {
        val fout = assertThrows(WebApplicationException::class.java, blok)

        assertEquals(503, fout.response.status)
        assertEquals(Foutcode.TE_VEEL_GELIJKTIJDIGE_DOWNLOADS, (fout as FbsFoutException).foutcode)
    }

    @Test
    fun `boven het plafond per ontvanger volgt een 503, en een andere ontvanger kan nog`() {
        val streams = streams()

        streams.reserveer(eerste)

        teVeel { streams.reserveer(eerste) }
        streams.reserveer(tweede)
        assertEquals(2, streams.aantalOpen)
    }

    @Test
    fun `boven het plafond van de pod volgt een 503, ook voor een nieuwe ontvanger`() {
        val streams = streams()

        streams.reserveer(eerste)
        streams.reserveer(tweede)

        teVeel { streams.reserveer(derde) }
        assertEquals(2, streams.aantalOpen, "een geweigerde plek telt niet mee")
    }

    @Test
    fun `een vrijgegeven plek komt terug, en een tweede vrijgave doet niets`() {
        val streams = streams()
        val vrijgave = streams.reserveer(eerste)

        vrijgave()
        vrijgave()

        assertEquals(0, streams.aantalOpen)
        streams.reserveer(eerste)
        teVeel { streams.reserveer(eerste) }
    }

    @Test
    fun `een weigering per ontvanger laat geen plek van de pod bezet`() {
        val streams = streams(max = 3, perOntvanger = 1)

        streams.reserveer(eerste)
        teVeel { streams.reserveer(eerste) }

        streams.reserveer(tweede)
        streams.reserveer(derde)
        assertEquals(3, streams.aantalOpen)
    }

    @ParameterizedTest(name = "max={0} perOntvanger={1} stilstand={2}")
    @CsvSource(
        "0, 1, PT60S",
        "2, 0, PT60S",
        "2, 3, PT60S",
        "2, 1, PT0S",
        "2, 1, -PT1S",
        "2, 1, PT720H",
    )
    fun `een verkeerde instelling faalt bij het opstarten`(max: Int, perOntvanger: Int, stilstand: String) {
        assertThrows(IllegalArgumentException::class.java) {
            streams(max, perOntvanger, Duration.parse(stilstand))
        }
    }
}
