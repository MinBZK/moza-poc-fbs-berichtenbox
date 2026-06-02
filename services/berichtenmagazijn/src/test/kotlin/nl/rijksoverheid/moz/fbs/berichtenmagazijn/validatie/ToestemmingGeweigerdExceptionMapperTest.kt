package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import nl.rijksoverheid.moz.fbs.common.exception.Problem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class ToestemmingGeweigerdExceptionMapperTest {

    private val mapper = ToestemmingGeweigerdExceptionMapper()

    // OIN met leidende nullen zodat het gemaskeerde prefix `0000***` is en we hard
    // kunnen aantonen dat de volledige 20-cijferige waarde niet in de log belandt.
    private val afzender = Oin("00001234567890123456")

    private val julLogger: Logger = Logger.getLogger(ToestemmingGeweigerdExceptionMapper::class.java.name)
    private val records = mutableListOf<LogRecord>()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records.add(record)
        }
        override fun flush() {}
        override fun close() {}
    }

    @BeforeEach
    fun installHandler() {
        julLogger.addHandler(handler)
        julLogger.level = Level.ALL
        records.clear()
    }

    @AfterEach
    fun removeHandler() {
        julLogger.removeHandler(handler)
    }

    private fun loggedOutput(): String =
        records.joinToString("\n") { it.message }

    @Test
    fun `geenProfiel-factory draagt reden en afzender en bouwt client-boodschap`() {
        val ex = ToestemmingGeweigerdException.geenProfiel(afzender)

        assertEquals("ontvanger heeft geen profiel", ex.reden)
        assertEquals(afzender, ex.afzender)
        assertEquals("Ontvanger heeft geen profiel bij MOZA voor afzender ${afzender.waarde}", ex.message)
    }

    @Test
    fun `geenActieveVoorkeur-factory draagt reden en afzender`() {
        val ex = ToestemmingGeweigerdException.geenActieveVoorkeur(afzender)

        assertEquals("geen actieve berichtenbox-voorkeur", ex.reden)
        assertEquals(afzender, ex.afzender)
    }

    @Test
    fun `mapt naar 403 en behoudt boodschap in detail`() {
        val response = mapper.toResponse(ToestemmingGeweigerdException.geenProfiel(afzender))

        assertEquals(403, response.status)
        val problem = response.entity as Problem
        assertEquals(403, problem.status)
        assertEquals("Forbidden", problem.title)
        assertEquals("Ontvanger heeft geen profiel bij MOZA voor afzender ${afzender.waarde}", problem.detail)
    }

    @Test
    fun `logt reden en gemaskeerd afzender-prefix`() {
        mapper.toResponse(ToestemmingGeweigerdException.geenActieveVoorkeur(afzender))

        val output = loggedOutput()
        assertTrue(output.contains("reden=geen actieve berichtenbox-voorkeur"), "reden ontbreekt: $output")
        assertTrue(output.contains("afzender=0000***"), "gemaskeerd afzender-prefix ontbreekt: $output")
    }

    @Test
    fun `logt NOOIT de volledige afzender-OIN (AVG)`() {
        mapper.toResponse(ToestemmingGeweigerdException.geenProfiel(afzender))

        val output = loggedOutput()
        assertFalse(
            output.contains(afzender.waarde),
            "volledige afzender-OIN mag niet in de log staan — gevonden: $output",
        )
    }
}
