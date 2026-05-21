package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.mockk.every
import io.mockk.mockk
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Borgt het ISE-onderscheid in [PublicatieDeliveriesOpschoner.verwijderTerminaleRijen]:
 *
 *  - **shutdown-window** (`entityManager.isOpen == false`): geen ERROR/WARN, alleen
 *    INFO. Een graceful shutdown waarbij de scheduler-trigger nog vuurt is normaal
 *    lifecycle-gedrag — niet pageworthy.
 *  - **steady-state** (`entityManager.isOpen == true`): ERROR, want een onverwachte
 *    transactiestate verdient inspectie en moet ops-alerts triggeren (WARN
 *    blijft in veel ops-stacks stil).
 *  - **`isOpen` zelf gooit ISE**: defensief afgevangen → behandeld als gesloten EM
 *    (INFO-pad). Voorkomt dat een al-disposed EM een secundaire fout produceert
 *    die de oorspronkelijke ISE maskeert.
 *
 * Een refactor die de `if (emOpen)`-tak inverteert maakt deploys luidruchtig
 * (ERROR-spam tijdens normale shutdown) zonder dat een test failt — daarom dit
 * mock-based pin-contract.
 */
class PublicatieDeliveriesOpschonerIseTest {

    private val entityManager = mockk<EntityManager>()
    private val opschoner = PublicatieDeliveriesOpschoner(entityManager)

    private val julLogger: Logger = Logger.getLogger(PublicatieDeliveriesOpschoner::class.java.name)
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

    private fun stubExecuteUpdateThrowsIse() {
        val query = mockk<Query>()
        every { entityManager.createNativeQuery(any<String>()) } returns query
        every { query.executeUpdate() } throws IllegalStateException("EM closed")
    }

    @Test
    fun `ISE met gesloten EntityManager logt INFO geen ERROR`() {
        stubExecuteUpdateThrowsIse()
        every { entityManager.isOpen } returns false

        opschoner.verwijderTerminaleRijen()

        val errorRecords = records.filter { it.level == Level.SEVERE }
        assertTrue(errorRecords.isEmpty(), "verwacht GEEN error bij shutdown — gevonden: $errorRecords")

        val infoRecords = records.filter { it.level == Level.INFO }
        assertEquals(1, infoRecords.size, "verwacht 1 info-regel — gevonden: ${infoRecords.map { it.message }}")
        assertTrue(
            infoRecords.first().message.contains("EntityManager gesloten"),
            "info-message moet shutdown-context expliciet noemen — gevonden: ${infoRecords.first().message}",
        )
    }

    @Test
    fun `ISE met open EntityManager logt ERROR met inspectie-prompt`() {
        stubExecuteUpdateThrowsIse()
        every { entityManager.isOpen } returns true

        opschoner.verwijderTerminaleRijen()

        val errorRecords = records.filter { it.level == Level.SEVERE }
        assertEquals(1, errorRecords.size, "verwacht 1 error voor steady-state ISE — gevonden: ${errorRecords.map { it.message }}")
        assertTrue(
            errorRecords.first().message.contains("buiten shutdown-window"),
            "error-message moet aangeven dat dit géén shutdown is — gevonden: ${errorRecords.first().message}",
        )
    }

    @Test
    fun `isOpen die zelf ISE gooit valt terug op shutdown-pad`() {
        stubExecuteUpdateThrowsIse()
        every { entityManager.isOpen } throws IllegalStateException("disposed EM")

        opschoner.verwijderTerminaleRijen()

        val errorRecords = records.filter { it.level == Level.SEVERE }
        assertTrue(
            errorRecords.isEmpty(),
            "isOpen-ISE moet behandeld worden als gesloten EM (geen ERROR) — gevonden: $errorRecords",
        )
        val infoRecords = records.filter { it.level == Level.INFO }
        assertEquals(1, infoRecords.size, "verwacht 1 info bij dubbele ISE — gevonden: $infoRecords")
    }
}
