package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.OpruimUitkomst
import nl.rijksoverheid.moz.fbs.berichtensessiecache.sessie.Opruiming
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecache
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.MockSessiecacheProfile
import nl.rijksoverheid.moz.fbs.kern.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.kern.identificatie.Kvk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/** Wat de wrapper van één logregel te zien kreeg. */
data class VeegRegel(val activiteit: String?, val type: String?, val waarde: String?, val status: StatusCode?)

/** Gedeelde opzet: een opgevangen logboek en een opgevangen applicatielog. */
abstract class SessieVegerTestBasis {

    @Inject
    lateinit var sessiecache: MockSessiecache

    protected val logregels = CopyOnWriteArrayList<VeegRegel>()
    protected val aanleidingen = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun opzet() {
        sessiecache.reset()
        logregels.clear()
        aanleidingen.clear()

        val handler = mockk<ProcessingHandler>(relaxed = true)
        val span = mockk<Span>(relaxed = true)

        every { handler.startSpan(any(), any()) } returns span
        every { span.setAttribute(SessieLogboek.AANLEIDING_ATTRIBUUT, any<String>()) } answers {
            aanleidingen += secondArg<String>()
            span
        }
        every { handler.addLogboekContextToSpan(any(), any(), any()) } answers {
            val context = secondArg<LogboekContext>()
            logregels += VeegRegel(context.processingActivityId, context.dataSubjectType, context.dataSubjectId, context.status)

            emptyList()
        }

        QuarkusMock.installMockForType(handler, ProcessingHandler::class.java)
    }

    protected fun vangLog(blok: () -> Unit): List<String> {
        val regels = CopyOnWriteArrayList<String>()
        val logger = Logger.getLogger(SessieVeger::class.java.name)
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

/** De scheduler staat uit: de tests roepen de ronde zelf aan, zodat er geen tick tussen kan komen. */
class SessieVegerHandmatigProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = MockSessiecacheProfile().enabledAlternatives

    override fun getConfigOverrides(): Map<String, String> =
        MockSessiecacheProfile().configOverrides + mapOf("quarkus.scheduler.enabled" to "false")
}

@QuarkusTest
@TestProfile(SessieVegerHandmatigProfile::class)
class SessieVegerTest : SessieVegerTestBasis() {

    @Inject
    lateinit var veger: SessieVeger

    companion object {
        @JvmStatic
        fun uitkomsten(): List<Arguments> = listOf(
            Arguments.of(OpruimUitkomst.GEWIST, StatusCode.OK, null),
            Arguments.of(OpruimUitkomst.MISLUKT, StatusCode.ERROR, "volgende ronde probeert opnieuw"),
            Arguments.of(OpruimUitkomst.ONBEVESTIGD, StatusCode.ERROR, "Redis-TTL"),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("uitkomsten")
    fun `elke uitkomst komt als verlopen opruiming in het logboek, en alleen een misser in de log`(
        uitkomst: OpruimUitkomst,
        status: StatusCode,
        waarschuwing: String?,
    ) {
        sessiecache.opruimingen.set(listOf(Opruiming(Bsn("999990019"), uitkomst)))

        val regels = vangLog { veger.veeg() }

        assertEquals(listOf(VeegRegel(ProcessingActivities.UITVRAAG_SESSIE, "BSN", "999990019", status)), logregels.toList())
        assertEquals(listOf("verlopen"), aanleidingen.toList())

        if (waarschuwing == null) {
            assertTrue(regels.isEmpty(), regels.toString())
        } else {
            assertEquals(1, regels.size, regels.toString())
            assertTrue(regels.single().contains(waarschuwing), regels.toString())
            assertFalse(regels.single().contains("999990019"), "de BSN hoort niet in de applicatielog")
        }
    }

    @Test
    fun `meerdere ontvangers worden elk gelogd en een ronde zonder verlopen sessies logt niets`() {
        sessiecache.opruimingen.set(
            listOf(
                Opruiming(Bsn("999990019"), OpruimUitkomst.GEWIST),
                Opruiming(Kvk("90000011"), OpruimUitkomst.MISLUKT),
            ),
        )

        veger.veeg()

        assertEquals(listOf("999990019", "90000011"), logregels.map { it.waarde })

        logregels.clear()
        veger.veeg()

        assertTrue(logregels.isEmpty())
    }

    @Test
    fun `een onbereikbare cache gooit niet en laat de volgende ronde opnieuw proberen`() {
        sessiecache.veegFout = SessiecacheException.Onbereikbaar("redis weg bij 999990019")

        val regels = vangLog { veger.veeg() }

        assertEquals(1, regels.size, regels.toString())
        assertTrue(regels.single().contains("volgende ronde probeert opnieuw"), regels.toString())
        assertFalse(regels.single().contains("999990019"), "de foutmelding hoort niet in de applicatielog")
        assertTrue(logregels.isEmpty())

        sessiecache.veegFout = null
        sessiecache.opruimingen.set(listOf(Opruiming(Bsn("999990019"), OpruimUitkomst.GEWIST)))
        veger.veeg()

        assertEquals(1, logregels.size)
    }
}

/** Korte tick, om te bewijzen dat de scheduler de ronde zelf aanroept. */
class SessieVegerGeplandProfile : QuarkusTestProfile {

    override fun getEnabledAlternatives(): Set<Class<*>> = MockSessiecacheProfile().enabledAlternatives

    override fun getConfigOverrides(): Map<String, String> =
        MockSessiecacheProfile().configOverrides + mapOf("berichtensessiecache.sessie.veeg-interval" to "PT1S")
}

@QuarkusTest
@TestProfile(SessieVegerGeplandProfile::class)
class SessieVegerGeplandTest : SessieVegerTestBasis() {

    @Test
    fun `een verlopen sessie wordt binnen twee veegintervallen opgeruimd en gelogd`() {
        sessiecache.opruimingen.set(listOf(Opruiming(Bsn("999990019"), OpruimUitkomst.GEWIST)))

        val einde = System.nanoTime() + 10_000_000_000L

        while (logregels.isEmpty() && System.nanoTime() < einde) Thread.sleep(50)

        assertEquals(listOf(VeegRegel(ProcessingActivities.UITVRAAG_SESSIE, "BSN", "999990019", StatusCode.OK)), logregels.toList())
        assertTrue(sessiecache.veegAanroepen.get() >= 1)
    }
}
