package nl.rijksoverheid.moz.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.mockk
import jakarta.persistence.PersistenceException
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.berichtenmagazijn.opslag.IdentificatienummerType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Borg dat de applicatielogs van [BerichtOpslagService] geen identificatienummer-
 * waarden van de ontvanger bevatten. Ontvanger kan een BSN zijn; persoonsgegevens
 * horen niet in de reguliere log (AVG art. 5 lid 1c, BIO 12.4.1). LDV is de juiste
 * plek voor `dataSubjectId`.
 *
 * De test gebruikt geen `@QuarkusTest`: we roepen de service rechtstreeks aan met
 * een gemockte repository. De circuit-breaker- en transaction-interceptors zijn
 * dan no-op, wat precies is wat we willen — we testen het log-bericht, niet het
 * CDI-gedrag.
 */
class BerichtOpslagServiceLoggingTest {

    private val repository = mockk<BerichtRepository>(relaxed = true)
    private val service = BerichtOpslagService(repository)

    private val julLogger: Logger = Logger.getLogger(BerichtOpslagService::class.java.name)
    private val records = mutableListOf<LogRecord>()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records.add(record)
        }
        override fun flush() {}
        override fun close() {}
    }

    // Sample BSN dat de elfproef doorstaat — mocht dit ooit in een logregel belanden,
    // dan herkennen we dat makkelijk in de assertion-output.
    private val bsn = "999993653"

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

    /** JBoss Logger schrijft het al-geformatteerde bericht naar `LogRecord.message`. */
    private fun formatted(record: LogRecord): String =
        record.parameters?.takeIf { it.isNotEmpty() }
            ?.let { params -> runCatching { String.format(record.message, *params) }.getOrDefault(record.message) }
            ?: record.message

    @Test
    fun `debug-log na succes bevat ontvangerType maar geen BSN-waarde`() {
        service.opslaanBericht(
            afzender = "00000001003214345000",
            ontvangerType = IdentificatienummerType.BSN,
            ontvangerWaarde = bsn,
            onderwerp = "Test",
            inhoud = "Test",
        )

        val output = records.joinToString("\n") { formatted(it) }
        assertFalse(
            output.contains(bsn),
            "BSN-waarde mag niet in de applicatielog staan — gevonden in: $output",
        )
        assertTrue(
            output.contains("ontvangerType=BSN"),
            "ontvangerType hoort wél in de log voor diagnose — output: $output",
        )
    }

    @Test
    fun `error-log bij opslagfout bevat ontvangerType maar geen BSN-waarde`() {
        every { repository.save(any<Bericht>()) } throws PersistenceException("infra fout")

        assertThrows(PersistenceException::class.java) {
            service.opslaanBericht(
                afzender = "00000001003214345000",
                ontvangerType = IdentificatienummerType.BSN,
                ontvangerWaarde = bsn,
                onderwerp = "Test",
                inhoud = "Test",
            )
        }

        val errorRecords = records.filter { it.level.intValue() >= Level.SEVERE.intValue() }
        assertTrue(errorRecords.isNotEmpty(), "verwacht minstens één SEVERE/ERROR logregel")

        val output = errorRecords.joinToString("\n") { formatted(it) }
        assertFalse(
            output.contains(bsn),
            "BSN-waarde mag niet in de error-log staan — gevonden in: $output",
        )
        assertTrue(
            output.contains("ontvangerType=BSN"),
            "ontvangerType hoort wél in de error-log voor diagnose — output: $output",
        )
        assertTrue(
            output.contains("Opslaan mislukt"),
            "error-log moet herkenbare prefix hebben — output: $output",
        )
    }
}
