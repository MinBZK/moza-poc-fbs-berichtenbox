package nl.rijksoverheid.moz.fbs.common.exception

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class UncaughtExceptionMapperTest {

    private val mapper = UncaughtExceptionMapper()

    private val julLogger: Logger = Logger.getLogger(UncaughtExceptionMapper::class.java.name)
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
    fun `vertaalt willekeurige exception naar 500 problem zonder details te lekken`() {
        val exception = IOException("ClickHouse onbereikbaar: connection refused at /1.2.3.4:8123")

        val response = mapper.toResponse(exception)

        assertEquals(500, response.status)
        assertEquals("application/problem+json", response.mediaType.toString())

        val problem = response.entity as Problem
        assertEquals("Internal Server Error", problem.title)
        assertEquals(500, problem.status)
        assertEquals(
            "Er is een onverwachte interne fout opgetreden. Vermeld errorId bij contact met support.",
            problem.detail,
        )
        assertNotNull(problem.instance)
        assertTrue(problem.instance!!.toString().startsWith("urn:uuid:"))
    }

    @Test
    fun `errorlog bevat GEEN exception-message (PII-protectie, consistent met 4xx-5xx)`() {
        // Borgt Round 11 L1: log laat exception.message bewust weg omdat
        // FoutBeschrijving.saneer geen niet-numerieke PII (namen, e-mail)
        // dekt. Cause-type blijft als 2e correlatie-handvat. Refactor die
        // exception.message terugzet zou PII naar applicatielog lekken.
        val exception = IOException("Upstream-fout voor Jan de Vries (jan@example.nl) en BSN 999993653")

        mapper.toResponse(exception)

        val errorRecords = records.filter { it.level == Level.SEVERE }
        assertEquals(1, errorRecords.size, "verwacht 1 error-regel — gevonden: $errorRecords")
        // errorf formatteert eager — rec.message is al-gerenderd.
        val formatted = errorRecords.first().message
        assertFalse(
            formatted.contains("Jan de Vries"),
            "naam-PII mag niet in errorlog — gevonden: $formatted",
        )
        assertFalse(
            formatted.contains("jan@example.nl"),
            "e-mail PII mag niet in errorlog — gevonden: $formatted",
        )
        assertFalse(
            formatted.contains("999993653"),
            "BSN mag niet in errorlog — gevonden: $formatted",
        )
        assertTrue(
            formatted.contains("IOException"),
            "type-correlatie moet aanwezig zijn — gevonden: $formatted",
        )
        assertTrue(
            formatted.contains("errorId"),
            "errorId-correlatie moet aanwezig zijn — gevonden: $formatted",
        )
    }
}
