package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.DataSubject
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logregel
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.io.IOException
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Borgt aan [BijlageUitkomst]: alleen het fouttype gaat het logboek in, een verloren uitkomst valt
 * op met een vast token, en vastleggen gooit nooit.
 */
class BijlageUitkomstTest {

    private val processingHandler = mockk<ProcessingHandler>()
    private val uitkomst = BijlageUitkomst(processingHandler)
    private val oorzaak = BijlageOverdrachtAfgebroken(IOException("Connection reset; BSN 999990019"))
    private val kenmerken = "bijlage, berichtId=5f0c3e4a-0000-4000-8000-000000000001 magazijnId=00000009900000000572"
    private val logregel = Logregel(
        SpanContext.create("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault()),
        "uitvraag-bijlage",
        "uitvraag-lezen",
        DataSubject("999990019", "BSN"),
    )

    private val julLogger: Logger = Logger.getLogger(BijlageUitkomst::class.java.name)
    private val records = mutableListOf<LogRecord>()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records.add(record)
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    private val meldingen: List<String> get() = records.map { it.message }

    @BeforeEach
    fun installHandler() {
        julLogger.addHandler(handler)
        julLogger.level = Level.ALL
        records.clear()
    }

    @AfterEach
    fun removeHandler() {
        julLogger.removeHandler(handler)
        Thread.interrupted()
    }

    @Test
    fun `alleen het type van de fout gaat het logboek in`() {
        val samenvatting = slot<Throwable>()
        every { processingHandler.recordFailedOutcome(listOf(logregel), capture(samenvatting)) } returns emptyList()

        uitkomst.legMislukkingVast(logregel, oorzaak, kenmerken)

        assertEquals(BijlageOverdrachtAfgebroken::class.java.name, samenvatting.captured.message)
        assertTrue(meldingen.isEmpty(), "een vastgelegde uitkomst hoort niets te melden — was: $meldingen")
    }

    @Test
    fun `een niet vastgelegde uitkomst valt op met het token en de kenmerken`() {
        every { processingHandler.recordFailedOutcome(any<List<Logregel>>(), any()) } returns listOf(logregel)

        uitkomst.legMislukkingVast(logregel, oorzaak, kenmerken)

        val melding = meldingen.single()
        assertTrue(melding.startsWith(BijlageUitkomst.ALERT_TOKEN), melding)
        assertTrue(melding.contains(kenmerken), melding)
        assertTrue(melding.contains("${logregel.spanContext.traceId}:${logregel.spanContext.spanId}"), melding)
        assertFalse(melding.contains("999990019"), melding)
    }

    @Test
    fun `een wrapper die gooit, gooit niet door en meldt het token zonder de message`() {
        every { processingHandler.recordFailedOutcome(any<List<Logregel>>(), any()) } throws IllegalStateException("rij met BSN 999990019")

        assertDoesNotThrow { uitkomst.legMislukkingVast(logregel, oorzaak, kenmerken) }

        assertTrue(meldingen.all { it.startsWith(BijlageUitkomst.ALERT_TOKEN) }, "$meldingen")
        assertTrue(meldingen.any { it.contains(IllegalStateException::class.java.name) }, "$meldingen")
        assertFalse(meldingen.any { it.contains("999990019") }, "$meldingen")
    }

    @Test
    fun `een ontbrekende signatuur in de wrapper komt met haar message in de log`() {
        every { processingHandler.recordFailedOutcome(any<List<Logregel>>(), any()) } throws NoSuchMethodError("recordFailedOutcome(List,Throwable)")

        uitkomst.legMislukkingVast(logregel, oorzaak, kenmerken)

        assertTrue(meldingen.any { it.contains("recordFailedOutcome(List,Throwable)") }, "$meldingen")
    }

    @Test
    fun `een interrupt tijdens het vastleggen blijft staan`() {
        every { processingHandler.recordFailedOutcome(any<List<Logregel>>(), any()) } throws InterruptedException()

        uitkomst.legMislukkingVast(logregel, oorzaak, kenmerken)

        assertTrue(Thread.currentThread().isInterrupted)
    }

    @Test
    fun `de logregel van deze actie komt uit de lopende span en de betrokkene van de context`() {
        val context = LogboekContext().apply { addSubject("999990019", "BSN") }

        val opgebouwd = Span.wrap(logregel.spanContext).makeCurrent().use {
            BijlageUitkomst.logregelVanDezeActie(context, "uitvraag-bijlage", "uitvraag-lezen")
        }

        assertEquals(logregel, opgebouwd)
    }
}
