package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.context.Context
import jakarta.transaction.HeuristicCommitException
import jakarta.transaction.HeuristicMixedException
import jakarta.transaction.HeuristicRollbackException
import jakarta.transaction.RollbackException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.UriBuilder
import jakarta.ws.rs.core.UriInfo
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekWriteException
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logregel
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtAanleverenRequest
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Identificatienummer as IdentificatienummerDto
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv.MislukteUitkomst
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieConfig
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.transaction.xa.XAException

/**
 * Borgt het logboek-gedrag van [AanleverResource]:
 *  1. **Logregel vóór opslag**: de LDV-schrijfactie wordt bevestigd vóórdat het bericht
 *     wordt opgeslagen. Een aanlevering die niet in het logboek kwam, laat dus geen
 *     bericht en geen publicatie-levering achter — anders zou een retry met een nieuw
 *     `berichtId` een duplicaat opleveren waar downstream-dedup niet op aanslaat.
 *  2. **Geen persoonsgegevens in de foutattributen**: de wrapper zet `exception.message`
 *     op dezelfde child-spans die `dpl.core.data_subject_id` dragen; alleen het type van
 *     een fout mag daarheen.
 *  3. **Uitkomst achteraf**: mislukt de opslag ná de bevestigde logregel, dan krijgt die
 *     logregel een ERROR-child — anders leest hij als geslaagde aanlevering.
 *  4. **dataSubjectType correlatie-parity**: het `dpl.core.data_subject_id_type`-veld
 *     bevat de concrete type-naam (BSN/RSIN/KVK), niet de relationele rol "ontvanger".
 *     Anders correleert het LDV-record niet met dat van [PublicatieClaimVerwerker].
 *
 * Geen `@QuarkusTest` nodig — we instantiëren de resource direct met mocks
 * (analoog aan [BerichtOpslagServiceTest]); CDI/proxy-laag is niet onder test.
 */
class AanleverResourceLdvTest {

    private val opslagService = mockk<BerichtOpslagService>()
    private val logboekContext = LogboekContext()
    private val processingHandler = mockk<ProcessingHandler>()
    private val logregels = listOf(Logregel(SpanContext.getInvalid(), "logregel", null, null))
    private val publicatieConfig = mockk<PublicatieConfig>()
    private val span = mockk<Span>(relaxed = true)
    private val uriInfo = mockk<UriInfo>().apply {
        every { baseUriBuilder } answers { UriBuilder.fromUri(URI.create("http://localhost/")) }
    }
    private val httpHeaders = mockk<HttpHeaders>().apply {
        every { getHeaderString("traceparent") } returns null
        every { requestHeaders } returns MultivaluedHashMap()
    }

    private val resource = AanleverResource(
        opslagService = opslagService,
        logboekContext = logboekContext,
        processingHandler = processingHandler,
        publicatieConfig = publicatieConfig,
        uriInfo = uriInfo,
        httpHeaders = httpHeaders,
    )

    private val request = BerichtAanleverenRequest().apply {
        afzender = "00000001003214345000"
        ontvanger = IdentificatienummerDto().apply {
            type = IdentificatienummerDto.TypeEnum.BSN
            waarde = "999993653"
        }
        onderwerp = "Test"
        inhoud = "Inhoud"
    }

    private val gevalideerdBericht = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = Oin("00000001003214345000"),
        ontvanger = Bsn("999993653"),
        onderwerp = "Test",
        inhoud = "Inhoud",
        tijdstipOntvangst = Instant.parse("2026-05-13T10:00:00Z"),
        publicatietijdstip = Instant.parse("2026-05-13T10:00:00Z"),
    )

    private fun stubBaseline() {
        every { processingHandler.startSpan("aanleveren-bericht", any()) } returns span
        every { publicatieConfig.verwerkingsregisterAanleveren() } returns "https://register.example.com/aanleveren"
        every {
            opslagService.valideerAanlevering(
                afzender = any(),
                ontvangerType = any(),
                ontvangerWaarde = any(),
                onderwerp = any(),
                inhoud = any(),
                publicatietijdstip = any(),
            )
        } returns gevalideerdBericht
        justRun { opslagService.slaBerichtOp(any(), any()) }
    }

    @Test
    fun `de logregel is bevestigd voordat het bericht wordt opgeslagen`() {
        stubBaseline()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        resource.leverBerichtAan(request)

        verifyOrder {
            processingHandler.addLogboekContextToSpan(span, any<LogboekContext>(), any())
            span.end()
            processingHandler.enforceWriteAcknowledgement(true)
            opslagService.slaBerichtOp(gevalideerdBericht, any())
        }
    }

    @Test
    fun `een LDV-schrijffout laat het aanleveren falen zonder iets op te slaan`() {
        // Fail-closed: zou het bericht er al staan, dan levert de poller het af terwijl de
        // aanleveraar een 500 krijgt en opnieuw aanlevert — met een nieuw berichtId, dus
        // een nieuwe CloudEvent-id waarop downstream-dedup niet aanslaat.
        stubBaseline()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        every {
            processingHandler.enforceWriteAcknowledgement(true)
        } throws LogboekWriteException("Logregel kon niet in het Logboek worden opgeslagen")

        assertThrows<LogboekWriteException> { resource.leverBerichtAan(request) }

        verify { span.end() }
        verify(exactly = 0) { opslagService.slaBerichtOp(any(), any()) }
    }

    @Test
    fun `een fout uit addLogboekContextToSpan verhindert de opslag`() {
        stubBaseline()
        every {
            processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any())
        } throws IllegalStateException("ldv stuk")
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        assertThrows<IllegalStateException> { resource.leverBerichtAan(request) }

        verify { span.end() }
        verify(exactly = 0) { opslagService.slaBerichtOp(any(), any()) }
    }

    @Test
    fun `op het foutpad mag de acknowledgement de domeinfout niet maskeren`() {
        // Er propageert al een functionele fout; die moet de gebruiker bereiken, niet
        // een LDV-fout die er overheen komt.
        stubBaseline()
        every { opslagService.valideerAanlevering(any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("opslag stuk")
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(false) }

        val ex = assertThrows<IllegalStateException> { resource.leverBerichtAan(request) }

        assertEquals("opslag stuk", ex.message)
        verify { processingHandler.enforceWriteAcknowledgement(false) }
        verify { span.end() }
    }

    @Test
    fun `ook een Error bij het valideren staat als fout in de logregel en wordt niet gemaskeerd`() {
        // Zonder de fout leest de logregel als een geslaagde verwerking, en zou een
        // schrijffout van het logboek de Error vervangen.
        stubBaseline()
        every { opslagService.valideerAanlevering(any(), any(), any(), any(), any(), any(), any()) } throws
            StackOverflowError()
        val gemeld = slot<Throwable>()
        every {
            processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), capture(gemeld))
        } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        assertThrows<StackOverflowError> { resource.leverBerichtAan(request) }

        assertEquals(StackOverflowError::class.java.name, gemeld.captured.message)
        verify { processingHandler.enforceWriteAcknowledgement(false) }
        verify(exactly = 0) { processingHandler.enforceWriteAcknowledgement(true) }
    }

    @Test
    fun `op het foutpad mag ook addLogboekContextToSpan de domeinfout niet maskeren`() {
        // Deze code draait vanuit finally: gooien zou de domeinfout vervangen, waarna de
        // aanleveraar de échte reden van de afwijzing niet meer ziet.
        stubBaseline()
        every { opslagService.valideerAanlevering(any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("ontvanger onbekend")
        every {
            processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any())
        } throws IllegalArgumentException("ldv stuk")
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        val ex = assertThrows<IllegalStateException> { resource.leverBerichtAan(request) }

        assertEquals("ontvanger onbekend", ex.message)
        assertTrue(
            ex.suppressed.any { it is IllegalArgumentException },
            "de LDV-fout moet als suppressed meereizen — anders verdwijnt hij spoorloos",
        )
        verify { span.end() }
    }

    @Test
    fun `de propagerende fout gaat als type mee naar de LDV-context, zonder message`() {
        // Zonder een fout mee te geven overschrijft een optimistische OK uit de context de
        // ERROR-status en missen de per-betrokkene child-logregels hun exception-attributen.
        // De message blijft achter: die rijen dragen dpl.core.data_subject_id en gaan bij
        // een inzageverzoek naar buiten.
        stubBaseline()
        val gevoeligeFout = IllegalStateException(
            "ERROR: null value violates not-null constraint\n" +
                "  Detail: Failing row contains (1, 999993653, Beste heer, uw uitkering is gewijzigd).",
        )
        every { opslagService.valideerAanlevering(any(), any(), any(), any(), any(), any(), any()) } throws
            gevoeligeFout
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        val foutSlot = slot<Throwable>()
        justRun {
            processingHandler.addLogboekContextToSpan(span, any<LogboekContext>(), capture(foutSlot))
        }

        assertThrows<IllegalStateException> { resource.leverBerichtAan(request) }

        val doorgegeven = foutSlot.captured.message.orEmpty()
        assertFalse(doorgegeven.contains("999993653"), "BSN mag niet in het exception-attribuut — was: $doorgegeven")
        assertFalse(doorgegeven.contains("uitkering"), "berichtinhoud mag niet in het exception-attribuut")
        assertTrue(
            doorgegeven.contains("IllegalStateException"),
            "het type moet bruikbaar blijven voor diagnose — was: $doorgegeven",
        )
    }

    @Test
    fun `dataSubjectType krijgt concrete type BSN in plaats van relationele rol`() {
        stubBaseline()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        resource.leverBerichtAan(request)

        // Borgt parity met PublicatieClaimVerwerker; "ontvanger" zou LDV-correlatie
        // breken tussen aanleveren- en publiceren-records voor dezelfde subject.
        assertEquals("BSN", logboekContext.dataSubjectType)
        assertEquals("999993653", logboekContext.dataSubjectId)
    }

    @Test
    fun `traceparent-processor met CRLF wordt gesaneerd (geen log-injection)`() {
        stubBaseline()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { httpHeaders.getHeaderString("traceparent") } returns
            "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
        every { httpHeaders.getHeaderString("traceparent-processor") } returns
            "vendor=evil\r\nlog inject"

        val attribuutWaarde = slot<String>()
        every {
            span.setAttribute("dpl.core.foreign_operation.processor", capture(attribuutWaarde))
        } returns span

        resource.leverBerichtAan(request)

        // FoutBeschrijving.saneer strip't control-chars → geen CRLF in het attribuut.
        assertTrue(
            !attribuutWaarde.captured.contains("\n") && !attribuutWaarde.captured.contains("\r"),
            "CRLF mag niet in span-attribuut — gevonden: ${attribuutWaarde.captured}",
        )
    }

    @Test
    fun `traceparent-processor met PII-cijferreeks wordt geredact`() {
        stubBaseline()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { httpHeaders.getHeaderString("traceparent") } returns
            "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
        every { httpHeaders.getHeaderString("traceparent-processor") } returns
            "vendor BSN=999993653"

        val attribuutWaarde = slot<String>()
        every {
            span.setAttribute("dpl.core.foreign_operation.processor", capture(attribuutWaarde))
        } returns span

        resource.leverBerichtAan(request)

        // Saneer redact ≥7-cijfer-reeksen (defense-in-depth) — BSN mag niet in audit lekken.
        assertTrue(
            !attribuutWaarde.captured.contains("999993653"),
            "BSN mag niet in span-attribuut — gevonden: ${attribuutWaarde.captured}",
        )
    }

    @Test
    fun `gewone traceparent-processor wordt ongeschonden doorgelaten`() {
        stubBaseline()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { httpHeaders.getHeaderString("traceparent") } returns
            "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
        every { httpHeaders.getHeaderString("traceparent-processor") } returns
            "rijksoverheid.nl/ldv/v1.2"

        val attribuutWaarde = slot<String>()
        every {
            span.setAttribute("dpl.core.foreign_operation.processor", capture(attribuutWaarde))
        } returns span

        resource.leverBerichtAan(request)

        // Geen control-chars of cijferreeksen → waarde blijft intact.
        assertEquals("rijksoverheid.nl/ldv/v1.2", attribuutWaarde.captured)
    }

    @Test
    fun `inbound traceparent wordt als parent geadopteerd`() {
        stubBaseline()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        // Upstream is vertrouwd (auth aan de clusterrand): de span continueert de
        // inbound trace-context i.p.v. een nieuwe root te forceren.
        every { httpHeaders.getHeaderString("traceparent") } returns
            "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
        every { httpHeaders.getHeaderString("traceparent-processor") } returns null

        val parentSlot = slot<Context>()
        every {
            processingHandler.startSpan("aanleveren-bericht", capture(parentSlot))
        } returns span

        resource.leverBerichtAan(request)

        // Parent is de ambiente OTel-context (`Context.current()`), niet een geforceerde
        // null-root. In productie heeft Quarkus' HTTP-instrumentatie die context al uit de
        // inbound `traceparent` gevuld; hier borgen we dat de resource hem adopteert i.p.v.
        // hem te negeren. (Down­stream-propagatie gebeurt bewust níét — de outbox ontkoppelt.)
        assertEquals(Context.current(), parentSlot.captured)
    }

    @Test
    fun `een opslagfout na de bevestigde logregel krijgt een ERROR-child met alleen het type`() {
        stubBaseline()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        val opslagFout = IllegalStateException("Failing row contains (1, 999993653, Beste heer)")
        every { opslagService.slaBerichtOp(any(), any()) } throws opslagFout
        val fout = slot<Throwable>()
        every { processingHandler.recordFailedOutcome(logregels, capture(fout)) } returns emptyList()

        val ex = assertThrows<IllegalStateException> { resource.leverBerichtAan(request) }

        assertSame(opslagFout, ex, "de aanleveraar moet de oorspronkelijke fout krijgen")
        assertEquals(IllegalStateException::class.java.name, fout.captured.message)

        verifyOrder {
            processingHandler.enforceWriteAcknowledgement(true)
            opslagService.slaBerichtOp(gevalideerdBericht, any())
            processingHandler.recordFailedOutcome(logregels, any())
        }
    }

    @Test
    fun `ook een Error uit de opslag krijgt een ERROR-child`() {
        // De transactie rolt net zo goed terug; zonder uitkomst leest de logregel als geslaagd.
        stubBaseline()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { opslagService.slaBerichtOp(any(), any()) } throws StackOverflowError()
        every { processingHandler.recordFailedOutcome(logregels, any()) } returns emptyList()

        assertThrows<StackOverflowError> { resource.leverBerichtAan(request) }

        verify { processingHandler.recordFailedOutcome(logregels, any()) }
    }

    @ParameterizedTest
    @MethodSource("onzekereCommits")
    fun `een commit met onzekere uitkomst krijgt geen ERROR-child`(fout: Throwable) {
        // Het bericht kan toch zijn opgeslagen; "mislukt" zou dan te weinig registreren.
        stubBaseline()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { opslagService.slaBerichtOp(any(), any()) } throws fout

        val ex = assertThrows<Throwable> { resource.leverBerichtAan(request) }

        assertSame(fout, ex)
        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) }
    }

    @ParameterizedTest
    @MethodSource("zekereFouten")
    fun `een fout die de transactie zeker terugdraait krijgt een ERROR-child`(fout: Throwable) {
        stubBaseline()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { opslagService.slaBerichtOp(any(), any()) } throws fout
        every { processingHandler.recordFailedOutcome(logregels, any()) } returns emptyList()

        val ex = assertThrows<Throwable> { resource.leverBerichtAan(request) }

        assertSame(fout, ex)
        verify { processingHandler.recordFailedOutcome(logregels, any()) }
    }

    @Test
    fun `een cyclische oorzaak-keten laat de commit-toets niet hangen`() {
        val a = IllegalStateException("a")
        val b = IllegalStateException("b", a)
        a.initCause(b)

        assertFalse(AanleverResource.commitOnzeker(a))
    }

    @Test
    fun `een verloren uitkomst noemt het bericht, maar niet de ontvanger`() {
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records.add(record)
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        val logger = Logger.getLogger(MislukteUitkomst::class.java.name)
        logger.addHandler(handler)

        try {
            stubBaseline()
            every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
            justRun { processingHandler.enforceWriteAcknowledgement(any()) }
            every { opslagService.slaBerichtOp(any(), any()) } throws IllegalStateException("opslag stuk")
            every { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) } returns logregels

            assertThrows<IllegalStateException> { resource.leverBerichtAan(request) }
        } finally {
            logger.removeHandler(handler)
        }

        val melding = records.single { it.level == Level.SEVERE }.message
        assertTrue(melding.contains(gevalideerdBericht.berichtId.toString()), melding)
        assertFalse(melding.contains("999993653"), "geen BSN in de applicatielog — was: $melding")
    }

    @Test
    fun `een verloren uitkomst-logregel vervangt de opslagfout niet`() {
        stubBaseline()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { opslagService.slaBerichtOp(any(), any()) } throws IllegalStateException("opslag stuk")
        every { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) } returns logregels

        val ex = assertThrows<IllegalStateException> { resource.leverBerichtAan(request) }

        assertEquals("opslag stuk", ex.message)
    }

    @Test
    fun `een geslaagde aanlevering schrijft geen uitkomst-logregel`() {
        stubBaseline()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        resource.leverBerichtAan(request)

        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) }
    }

    @Test
    fun `een afwijzing voor de bevestiging krijgt geen ERROR-child, de logregel zelf staat al op ERROR`() {
        stubBaseline()
        every { opslagService.valideerAanlevering(any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalArgumentException("ontvanger onbekend")
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        assertThrows<IllegalArgumentException> { resource.leverBerichtAan(request) }

        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) }
    }

    companion object {
        @JvmStatic
        fun onzekereCommits(): List<Throwable> = listOf(
            RuntimeException("commit", HeuristicMixedException("gemengd")),
            RuntimeException("commit", HeuristicCommitException("vastgelegd")),
            HeuristicMixedException("direct"),
            // Verbinding weg tijdens de COMMIT: de bevestiging kan zijn weggevallen.
            commitFout(SQLException("I/O error", "08006")),
            // De backend viel weg nadat de COMMIT verstuurd was.
            commitFout(SQLException("terminating connection", "57P01")),
            commitFout(SQLException("zonder SQLState")),
            commitFout(null),
            // Dezelfde commit-fout, door een tussenlaag als oorzaak verpakt.
            IllegalStateException("opslag", commitFout(SQLException("I/O error", "08006"))),
        )

        @JvmStatic
        fun zekereFouten(): List<Throwable> = listOf(
            // Vóór de COMMIT: wat er ook misging, er is niets vastgelegd.
            IllegalStateException("opslag", SQLException("duplicate key", "23505")),
            IllegalStateException("opslag", SQLException("I/O error", "08006")),
            IllegalStateException("opslag", SQLException("pool uitgeput")),
            IllegalStateException("opslag", SQLException("datetime overflow", "22008")),
            RuntimeException("commit", HeuristicRollbackException("teruggedraaid")),
            // Tijdens de COMMIT, maar door de database afgewezen.
            commitFout(SQLException("deferred foreign key", "23503")),
            commitFout(SQLException("serialization failure", "40001")),
        )

        /**
         * De vorm waarin een mislukte `connection.commit()` de resource bereikt, zoals
         * `LdvUitkomstIntegrationTest` hem tegen een echte database vastlegt: de pool meldt de
         * fout als XAException, en de transactiemanager hangt die als suppressed aan een
         * RollbackException zonder oorzaak.
         */
        private fun commitFout(oorzaak: SQLException?): Throwable {
            val xaFout = XAException(XAException.XA_RBROLLBACK)
            oorzaak?.let(xaFout::initCause)

            return RollbackException("ARJUNA016053: Could not commit transaction.").apply { addSuppressed(xaFout) }
        }
    }
}
