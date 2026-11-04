package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.trace.SdkTracerProvider
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.DataSubject
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logregel
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Borgt drie dingen aan [OphaalUitkomst]:
 *  1. **De mislukking komt onder de juiste logregel**, en buiten de aanroepende thread — die is bij
 *     het slotevent de event-loop.
 *  2. **Een verloren uitkomst valt op**: zonder ERROR-child leest de ophaalronde als geslaagd; de
 *     melding draagt het token waarop een alert routeert.
 *  3. **Gooit niet**, ook niet als de wrapper of de executor het laat afweten.
 */
class OphaalUitkomstTest {

    private val processingHandler = mockk<ProcessingHandler>()
    private val taken = mutableListOf<Runnable>()
    private val uitkomst = OphaalUitkomst(processingHandler) { taken += it }
    private val mislukking = OphaalrondeMislukt("1 van 2 organisaties mislukt")
    private val kenmerken = "ophaalronde, ontvanger.type=BSN"

    private val logregel = Logregel(
        SpanContext.create("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault()),
        "uitvraag-ophalen-sse",
        "https://register.example.com/lezen",
        DataSubject("999993653", "BSN"),
    )

    private val julLogger: Logger = Logger.getLogger(OphaalUitkomst::class.java.name)
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

    @Test
    fun `de mislukking komt onder de logregel, op de executor`() {
        every { processingHandler.recordFailedOutcome(logregel, mislukking) } returns emptyList()

        uitkomst.legVast(logregel, mislukking, kenmerken)

        // Nog niet op de aanroepende thread: pas als de executor de taak draait.
        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Logregel>(), any()) }

        taken.single().run()

        verify(exactly = 1) { processingHandler.recordFailedOutcome(logregel, mislukking) }
        assertTrue(records.none { it.level == Level.SEVERE }, "een vastgelegde uitkomst meldt niets")
    }

    @Test
    fun `een verloren uitkomst gaat met het token en de kenmerken de applicatielog in`() {
        every { processingHandler.recordFailedOutcome(logregel, mislukking) } returns listOf(logregel)

        uitkomst.legVast(logregel, mislukking, kenmerken)
        taken.single().run()

        val melding = records.single { it.level == Level.SEVERE }.message

        assertTrue(melding.startsWith(OphaalUitkomst.ALERT_TOKEN), melding)
        assertTrue(melding.contains(kenmerken), melding)
        assertTrue(melding.contains(logregel.spanContext.spanId), melding)
        assertFalse(melding.contains("999993653"), "de melding hoort geen BSN te dragen: $melding")
    }

    @Test
    fun `een fout uit de wrapper gooit niet en meldt het token`() {
        // Een wrapper-versie zonder recordFailedOutcome geeft NoSuchMethodError; die mag de
        // ophaalronde niet raken.
        every { processingHandler.recordFailedOutcome(logregel, mislukking) } throws
            NoSuchMethodError("ProcessingHandler.recordFailedOutcome(Lnl/…/Logregel;)")

        uitkomst.legVast(logregel, mislukking, kenmerken)

        assertDoesNotThrow { taken.single().run() }

        val melding = records.single { it.level == Level.SEVERE }.message

        assertTrue(melding.startsWith(OphaalUitkomst.ALERT_TOKEN), melding)
        assertTrue(melding.contains("recordFailedOutcome"), "een LinkageError noemt de ontbrekende signatuur: $melding")
    }

    @Test
    fun `een onderbreking in de wrapper behoudt de interrupt-flag`() {
        every { processingHandler.recordFailedOutcome(logregel, mislukking) } throws InterruptedException()

        uitkomst.legVast(logregel, mislukking, kenmerken)

        try {
            taken.single().run()
        } finally {
            assertTrue(Thread.interrupted(), "de interrupt-flag moet hersteld zijn")
        }
    }

    @Test
    fun `een executor die weigert, gooit niet en meldt het token`() {
        val weigerend = OphaalUitkomst(processingHandler, Executor { throw RejectedExecutionException("vol") })

        assertDoesNotThrow { weigerend.legVast(logregel, mislukking, kenmerken) }

        val melding = records.single { it.level == Level.SEVERE }.message

        assertTrue(melding.startsWith(OphaalUitkomst.ALERT_TOKEN), melding)
        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Logregel>(), any()) }
    }

    @Test
    fun `de logregel van deze actie is de lopende span, met naam, activiteit en betrokkene`() {
        val context = LogboekContext().apply {
            dataSubjectId = "999993653"
            dataSubjectType = "BSN"
        }

        val span = SdkTracerProvider.builder().build().get("test").spanBuilder("uitvraag-ophalen-sse").startSpan()

        val gebouwd = span.makeCurrent().use {
            logregelVanDezeActie(context, "uitvraag-ophalen-sse", "https://register.example.com/lezen")
        }

        assertEquals(span.spanContext, gebouwd.spanContext)
        assertEquals("uitvraag-ophalen-sse", gebouwd.name)
        assertEquals("https://register.example.com/lezen", gebouwd.processingActivityId)
        assertEquals(DataSubject("999993653", "BSN"), gebouwd.subject)
    }
}
