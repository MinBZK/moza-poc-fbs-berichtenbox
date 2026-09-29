package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logregel
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Borgt dat een verloren uitkomst-logregel opvalt: zonder ERROR-child leest de logregel in
 * het logboek als geslaagd, en dat is onder-rapportage. De melding draagt een vast token
 * zodat een alert erop kan routeren; bij een volledig vastgelegde uitkomst blijft het stil.
 */
class MislukteUitkomstTest {

    private val processingHandler = mockk<ProcessingHandler>()
    private val mislukteUitkomst = MislukteUitkomst(processingHandler)
    private val fout = IllegalStateException("opslag stuk")

    private val julLogger: Logger = Logger.getLogger(MislukteUitkomst::class.java.name)
    private val records = mutableListOf<LogRecord>()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records.add(record)
        }

        override fun flush() = Unit

        override fun close() = Unit
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

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 3])
    fun `een volledig vastgelegde uitkomst meldt niets`(aantal: Int) {
        val logregels = logregels(aantal)
        every { processingHandler.recordFailedOutcome(logregels, fout) } returns emptyList()

        mislukteUitkomst.legVast(logregels, fout)

        assertTrue(records.none { it.level == Level.SEVERE }, "geen fout bij een vastgelegde uitkomst")
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 3])
    fun `een verloren uitkomst meldt het token en elke betrokken logregel`(aantal: Int) {
        val logregels = logregels(aantal)
        every { processingHandler.recordFailedOutcome(logregels, fout) } returns logregels

        mislukteUitkomst.legVast(logregels, fout)

        val melding = records.single { it.level == Level.SEVERE }.message
        assertTrue(melding.startsWith(MislukteUitkomst.ALERT_TOKEN), "token vooraan voor alert-routing — was: $melding")

        logregels.forEach { logregel ->
            assertTrue(melding.contains(logregel.spanContext.spanId), "span_id ${logregel.spanContext.spanId} ontbreekt")
        }
    }

    @Test
    fun `alleen de verloren logregels worden gemeld`() {
        val logregels = logregels(3)
        every { processingHandler.recordFailedOutcome(logregels, fout) } returns listOf(logregels[1])

        mislukteUitkomst.legVast(logregels, fout)

        val melding = records.single { it.level == Level.SEVERE }.message
        assertEquals(listOf(logregels[1].spanContext.spanId), logregels.map { it.spanContext.spanId }.filter(melding::contains))
    }

    private fun logregels(aantal: Int): List<Logregel> = (1..aantal).map { i ->
        val spanContext = SpanContext.create(
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b716920333$i",
            TraceFlags.getSampled(),
            TraceState.getDefault(),
        )
        Logregel(spanContext, "aanleveren-bericht", "https://register.example.com/aanleveren", null)
    }
}
