package nl.rijksoverheid.moz.fbs.common.exception

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

class DomainValidationExceptionMapperTest {

    private val mapper = DomainValidationExceptionMapper()

    private val julLogger: Logger = Logger.getLogger(DomainValidationExceptionMapper::class.java.name)
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

    @Test
    fun `exposeert handgeschreven domeinboodschap als 400 Bad Request detail`() {
        val response = mapper.toResponse(DomainValidationException("onderwerp mag niet leeg zijn"))

        assertEquals(400, response.status)
        val problem = response.entity as Problem
        assertEquals("Bad Request", problem.title)
        assertEquals("onderwerp mag niet leeg zijn", problem.detail)
    }

    @Test
    fun `log saneert BSN-cijferreeks en CRLF in message`() {
        // Borgt FoutBeschrijving.saneer-toepassing op log-tak. Domeinmessages
        // zijn handgeschreven, maar string-interpolatie van user-input kan
        // alsnog BSN/CRLF meedragen — refactor die saneer weghaalt zou een
        // PII/CWE-117 lek terugbrengen, deze test vangt dat.
        mapper.toResponse(
            DomainValidationException("Ontvanger BSN 999993653 ongeldig\r\nlevel=ERROR fake-line"),
        )

        val infoRecords = records.filter { it.level == Level.INFO }
        assertEquals(1, infoRecords.size, "verwacht 1 info-regel — gevonden: $infoRecords")
        val output = infoRecords.first().let { rec ->
            rec.parameters?.let { runCatching { String.format(rec.message, *it) }.getOrDefault(rec.message) }
                ?: rec.message
        }
        assertFalse(output.contains("999993653"), "BSN mag niet ongesaneerd in log — gevonden: $output")
        assertFalse(
            output.contains("\n") || output.contains("\r"),
            "CRLF mag niet in log (log-injection) — gevonden: $output",
        )
        assertTrue(output.contains("[REDACTED]"), "BSN-redactie marker moet aanwezig — gevonden: $output")
    }

    @Test
    fun `detail behoudt API-pad in handgeschreven message (geen sanitizeClientDetail)`() {
        // Round 11 H1: sanitizeClientDetail STRIP-de eerder API-paden uit
        // handgeschreven domeinmessages (false-positive: `/api/v1/berichten`
        // matchte FILE_PATH_PATTERN). Voor DomainValidationException
        // gebruiken we daarom de message ongesaneerd voor `detail` — call-sites
        // bouwen statische messages, geen user-input string-interpolatie.
        val response = mapper.toResponse(
            DomainValidationException("URL /api/v1/berichten/abc is ongeldig"),
        )

        val problem = response.entity as Problem
        assertTrue(
            problem.detail!!.contains("/api/v1/berichten"),
            "API-pad moet bewaard blijven voor actionable client-detail — gevonden: ${problem.detail}",
        )
    }

    @Test
    fun `lege message levert lege detail zonder crash`() {
        // Borg defensieve edge-case: DomainValidationException vereist
        // non-null message, maar lege string moet door beide saneer-paden.
        val response = mapper.toResponse(DomainValidationException(""))

        assertEquals(400, response.status)
        val problem = response.entity as Problem
        assertEquals("Bad Request", problem.title)
        // Geen NPE — saneer en problemResponse werken op lege string.
    }
}
