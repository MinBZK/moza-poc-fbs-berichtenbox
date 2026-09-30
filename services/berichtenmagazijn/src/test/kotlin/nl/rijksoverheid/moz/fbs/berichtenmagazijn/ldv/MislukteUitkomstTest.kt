package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logregel
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.DownstreamResultaat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Borgt drie dingen aan [MislukteUitkomst]:
 *  1. **Geen persoonsgegevens in het logboek**: de fout wordt hier samengevat, zodat geen
 *     aanroep een ruwe exceptie-message op een rij met de betrokkene kan zetten.
 *  2. **Een verloren uitkomst valt op**: zonder ERROR-child leest de logregel als geslaagd;
 *     de melding draagt een vast token plus de kenmerken van de verwerking.
 *  3. **Gooit niet**, ook niet als de wrapper zijn eigen contract breekt.
 */
class MislukteUitkomstTest {

    private val processingHandler = mockk<ProcessingHandler>()
    private val mislukteUitkomst = MislukteUitkomst(processingHandler)
    private val fout = IllegalStateException("Failing row contains (1, 999993653, Beste heer)")
    private val kenmerken = "aanleveren berichtId=5f0c3e4a-0000-4000-8000-000000000001"

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

    @Test
    fun `een exceptie gaat alleen als type het logboek in`() {
        val logregels = logregels(1)
        val samenvatting = slot<Throwable>()
        every { processingHandler.recordFailedOutcome(logregels, capture(samenvatting)) } returns emptyList()

        mislukteUitkomst.legVast(logregels, fout, kenmerken)

        assertEquals(IllegalStateException::class.java.name, samenvatting.captured.message)
    }

    @Test
    fun `een mislukte levering gaat alleen als categorie het logboek in`() {
        val logregels = logregels(1)
        val samenvatting = slot<Throwable>()
        every { processingHandler.recordFailedOutcome(logregels, capture(samenvatting)) } returns emptyList()

        mislukteUitkomst.legVast(
            logregels,
            DownstreamResultaat.NetwerkFout("geweigerd voor 999993653", zekerNietVerzonden = true),
            kenmerken,
        )

        assertEquals("NetwerkFout", samenvatting.captured.message)
    }

    @Test
    fun `zonder logregels wordt de wrapper niet aangeroepen`() {
        mislukteUitkomst.legVast(emptyList(), fout, kenmerken)

        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) }
        assertTrue(records.none { it.level == Level.SEVERE })
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 3])
    fun `een volledig vastgelegde uitkomst meldt niets`(aantal: Int) {
        val logregels = logregels(aantal)
        every { processingHandler.recordFailedOutcome(logregels, any()) } returns emptyList()

        mislukteUitkomst.legVast(logregels, fout, kenmerken)

        assertTrue(records.none { it.level == Level.SEVERE }, "geen fout bij een vastgelegde uitkomst")
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 3])
    fun `een verloren uitkomst meldt het token, de kenmerken en elke betrokken logregel`(aantal: Int) {
        val logregels = logregels(aantal)
        every { processingHandler.recordFailedOutcome(logregels, any()) } returns logregels

        mislukteUitkomst.legVast(logregels, fout, kenmerken)

        val melding = records.single { it.level == Level.SEVERE }.message
        assertTrue(melding.startsWith(MislukteUitkomst.ALERT_TOKEN), "token vooraan voor alert-routing — was: $melding")
        assertTrue(melding.contains(kenmerken), "de verwerking moet te herleiden zijn — was: $melding")
        assertFalse(melding.contains("999993653"), "geen persoonsgegevens in de applicatielog")

        logregels.forEach { logregel ->
            assertTrue(melding.contains(logregel.spanContext.spanId), "span_id ${logregel.spanContext.spanId} ontbreekt")
        }
    }

    @Test
    fun `alleen de verloren logregels worden gemeld`() {
        val logregels = logregels(3)
        every { processingHandler.recordFailedOutcome(logregels, any()) } returns listOf(logregels[1])

        mislukteUitkomst.legVast(logregels, fout, kenmerken)

        val melding = records.single { it.level == Level.SEVERE }.message
        assertEquals(listOf(logregels[1].spanContext.spanId), logregels.map { it.spanContext.spanId }.filter(melding::contains))
    }

    @Test
    fun `een fout uit de wrapper zelf gooit niet en meldt alle logregels als verloren`() {
        // Een wrapper-versie zonder recordFailedOutcome geeft NoSuchMethodError; die mag de
        // fout van de verwerking niet vervangen.
        val logregels = logregels(2)
        every { processingHandler.recordFailedOutcome(logregels, any()) } throws NoSuchMethodError("recordFailedOutcome")

        assertDoesNotThrow { mislukteUitkomst.legVast(logregels, fout, kenmerken) }

        val meldingen = records.filter { it.level == Level.SEVERE }.map { it.message }
        assertTrue(meldingen.all { it.startsWith(MislukteUitkomst.ALERT_TOKEN) }, "elke melding draagt het token")
        assertTrue(
            logregels.all { logregel -> meldingen.any { it.contains(logregel.spanContext.spanId) } },
            "alle logregels moeten als verloren gemeld zijn — was: $meldingen",
        )
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
