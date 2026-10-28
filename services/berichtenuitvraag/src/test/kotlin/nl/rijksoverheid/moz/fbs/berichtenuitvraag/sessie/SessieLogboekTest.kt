package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekWriteException
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.OpruimUitkomst
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.kern.identificatie.Kvk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * De logregel van het opruimen, los van een verzoek: zo schrijft ook de veegtaak hem. Het
 * schrijven via de interceptor en binnen een verzoek toetst `LdvSubjectRegistrationTest`.
 */
class SessieLogboekTest {

    private val span = mockk<Span>(relaxed = true)
    private val handler = mockk<ProcessingHandler>(relaxed = true).apply {
        every { startSpan(SessieLogboek.NAAM, any()) } returns span
    }
    private val logboek = SessieLogboek(handler)

    companion object {
        @JvmStatic
        fun gevallen(): List<Arguments> = listOf(
            Arguments.of(OpruimAanleiding.UITGELOGD, OpruimUitkomst.GEWIST, StatusCode.OK),
            Arguments.of(OpruimAanleiding.NIEUWE_START, OpruimUitkomst.GEWIST, StatusCode.OK),
            Arguments.of(OpruimAanleiding.VERLOPEN, OpruimUitkomst.GEWIST, StatusCode.OK),
            Arguments.of(OpruimAanleiding.VERLOPEN, OpruimUitkomst.MISLUKT, StatusCode.ERROR),
            Arguments.of(OpruimAanleiding.VERLOPEN, OpruimUitkomst.ONBEVESTIGD, StatusCode.ERROR),
        )
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("gevallen")
    fun `schrijft de ontvanger als betrokkene onder de sessie-activiteit`(
        aanleiding: OpruimAanleiding,
        uitkomst: OpruimUitkomst,
        status: StatusCode,
    ) {
        val context = slot<LogboekContext>()
        every { handler.addLogboekContextToSpan(span, capture(context), any()) } returns emptyList()

        logboek.opgeruimd(Kvk("90000011"), aanleiding, uitkomst)

        assertEquals(ProcessingActivities.UITVRAAG_SESSIE, context.captured.processingActivityId)
        assertEquals("KVK", context.captured.dataSubjectType)
        assertEquals("90000011", context.captured.dataSubjectId)
        assertEquals(status, context.captured.status)
        verify { span.setAttribute(SessieLogboek.AANLEIDING_ATTRIBUUT, aanleiding.waarde) }
        verify { span.setAttribute(SessieLogboek.UITKOMST_ATTRIBUUT, uitkomst.name.lowercase()) }
        verify { span.end() }
        verify { handler.enforceWriteAcknowledgement(true) }
    }

    @Test
    fun `een mislukte schrijfactie gooit niet, maar meldt zich zonder ontvanger`() {
        every { handler.enforceWriteAcknowledgement(true) } throws LogboekWriteException("logboek weg")

        val regels = vangLog { logboek.opgeruimd(Bsn("999990019"), OpruimAanleiding.UITGELOGD) }

        val melding = regels.single { it.contains(SessieLogboek.ALERT_TOKEN) }

        assertTrue(melding.contains("aanleiding=uitgelogd"), melding)
        assertFalse(melding.contains("999990019"), "de BSN hoort niet in de applicatielog: $melding")
    }

    /** Wat de privacy officer nodig heeft om het gat te melden: tellen, en per regel waarom. */
    @Test
    fun `het alert draagt per regel een eigen foutId, de uitkomst, het type ontvanger en de klasse van de oorzaak`() {
        every { handler.enforceWriteAcknowledgement(true) } throws LogboekWriteException("logboek weg")

        val regels = vangLog {
            logboek.opgeruimd(Bsn("999990019"), OpruimAanleiding.VERLOPEN, OpruimUitkomst.ONBEVESTIGD)
            logboek.opgeruimd(Bsn("999990019"), OpruimAanleiding.VERLOPEN, OpruimUitkomst.ONBEVESTIGD)
        }.filter { it.contains(SessieLogboek.ALERT_TOKEN) }

        val foutIds = regels.map { Regex("foutId=([0-9a-f-]{36})").find(it)?.groupValues?.get(1) }

        assertEquals(2, regels.size, regels.toString())
        assertTrue(foutIds.all { it != null } && foutIds.toSet().size == 2, "geen eigen foutId per regel: $regels")
        regels.forEach { regel ->
            assertTrue(regel.contains("uitkomst=ONBEVESTIGD"), regel)
            assertTrue(regel.contains("ontvanger.type=BSN"), regel)
            assertTrue(regel.contains("oorzaak=${LogboekWriteException::class.java.name}"), regel)
            assertFalse(regel.contains("999990019"), "de BSN hoort niet in de applicatielog: $regel")
        }
    }

    @Test
    fun `een Error gaat door, maar niet zonder het alert`() {
        every { handler.enforceWriteAcknowledgement(true) } throws NoClassDefFoundError("exporter")

        val regels = vangLog {
            assertThrows<NoClassDefFoundError> { logboek.opgeruimd(Bsn("999990019"), OpruimAanleiding.VERLOPEN) }
        }

        val melding = regels.single { it.contains(SessieLogboek.ALERT_TOKEN) }

        assertTrue(melding.contains("aanleiding=verlopen"), melding)
        assertFalse(melding.contains("999990019"), "de BSN hoort niet in de applicatielog: $melding")
    }

    private fun vangLog(blok: () -> Unit): List<String> {
        val regels = mutableListOf<String>()
        val logger = Logger.getLogger(SessieLogboek::class.java.name)
        val vanger = object : Handler() {
            override fun publish(record: LogRecord) {
                regels += String.format(record.message, *(record.parameters ?: emptyArray()))
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

        logger.addHandler(vanger)

        try {
            blok()
        } finally {
            logger.removeHandler(vanger)
        }

        return regels
    }
}
