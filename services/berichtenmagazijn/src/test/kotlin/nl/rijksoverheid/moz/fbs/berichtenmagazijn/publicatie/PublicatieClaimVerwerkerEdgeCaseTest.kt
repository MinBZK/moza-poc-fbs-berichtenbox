package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.mockk.Called
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.StatusCode
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekWriteException
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logregel
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ldv.MislukteUitkomst
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Borgt defensieve paden in [PublicatieClaimVerwerker]:
 *  1. **Duplicate-send venster**: downstream gaf 2xx, maar `markeerGeslaagd`
 *     gooit `IllegalStateException`. Verwerker moet ERROR-loggen en
 *     re-throwen zodat REQUIRES_NEW rolt; volgende pollronde retried met
 *     dezelfde UUIDv5-id (downstream dedupliceert).
 *  2. **Logregel-vóór-levering-volgorde**: de LDV-schrijfactie wordt bevestigd
 *     vóórdat het CloudEvent de deur uitgaat. Faalt de schrijfactie, dan mag er
 *     niet geleverd worden.
 *  3. **Uitkomst achteraf**: staat vast dat de levering niet aankwam, dan krijgt de al
 *     bevestigde logregel een ERROR-child. Een geslaagde of onzekere levering schrijft
 *     niets extra en leest daardoor als verstrekking.
 */
class PublicatieClaimVerwerkerEdgeCaseTest {

    private class DownstreamStub(private val u: String, private val max: Int = 3) : PublicatieConfig.Downstream {
        override fun url(): String = u
        override fun grantHash(): java.util.Optional<String> = java.util.Optional.empty()
        override fun maxPogingen(): Int = max
        override fun backoff(): PublicatieConfig.Backoff = object : PublicatieConfig.Backoff {
            override fun basis(): Duration = Duration.ofSeconds(1)
            override fun plafond(): Duration = Duration.ofHours(1)
        }
    }

    private val claimer = mockk<PublicatieClaimer>()
    private val berichten = mockk<BerichtRepository>()
    private val cloudEventBuilder = mockk<CloudEventBuilder>()
    private val downstreamClient = mockk<DownstreamClient>()
    private val config = mockk<PublicatieConfig>()
    private val processingHandler = mockk<ProcessingHandler>()
    private val logregels = listOf(Logregel(SpanContext.getInvalid(), "logregel", null, null))
    private val span = mockk<Span>(relaxed = true)
    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC)

    private val verwerker = PublicatieClaimVerwerker(
        claimer = claimer,
        berichten = berichten,
        cloudEventBuilder = cloudEventBuilder,
        downstreamClient = downstreamClient,
        config = config,
        processingHandler = processingHandler,
        clock = clock,
    )

    private val bericht = Bericht(
        berichtId = UUID.randomUUID(),
        afzender = Oin("00000001003214345000"),
        ontvanger = Bsn("999993653"),
        onderwerp = "X",
        inhoud = "x",
        tijdstipOntvangst = Instant.parse("2026-05-12T10:00:00Z"),
        publicatietijdstip = Instant.parse("2026-05-12T10:00:00Z"),
    )
    private val claim = PublicatieClaim(
        claimId = 7L,
        berichtId = bericht.berichtId,
        doel = Publicatiedoel("aanmeld"),
        pogingen = 0,
    )
    private val event = CloudEvent(
        id = "id-1", source = "src", specversion = "1.0", type = "t",
        subject = bericht.berichtId.toString(), time = clock.instant(),
        datacontenttype = "application/json",
        dataschema = "https://example/schema",
        data = BerichtData(
            berichtId = bericht.berichtId, afzender = bericht.afzender.waarde,
            ontvanger = OntvangerData("BSN", "999993653"),
            onderwerp = "X",
            tijdstipOntvangst = bericht.tijdstipOntvangst,
            publicatietijdstip = bericht.publicatietijdstip,
        ),
    )

    private fun stubClaimMetBericht() {
        every { claimer.claimNuVerwerkbaar(maxBatch = 1) } returns listOf(claim)
        every { berichten.findByBerichtId(claim.berichtId) } returns bericht
        every { processingHandler.startSpan(any<String>(), any()) } returns span
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub("http://localhost:1/events"))
        every { config.verwerkingsregisterPubliceren() } returns "https://register.example.com/x"
        every { cloudEventBuilder.bouw(bericht, claim.doel, any()) } returns event
    }

    @Test
    fun `markeerGeslaagd faalt na 2xx = duplicate-send venster gelogd en herthrown`() {
        // De logregel is dan al bevestigd (die gaat vóór de levering) — deze late
        // faalroute raakt het logboek niet meer, alleen de claim-status. Bewust geen
        // ERROR-child: de afnemer heeft het bericht ontvangen.
        stubClaimMetBericht()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns DownstreamResultaat.Geslaagd
        every { claimer.markeerGeslaagd(claim.claimId, any()) } throws
            IllegalStateException("delivery weg, contract gebroken")

        // Re-thrown zodat REQUIRES_NEW van caller rollbacked en volgende ronde retried.
        assertThrows<IllegalStateException> { verwerker.verwerkEenClaim() }
        verify { processingHandler.addLogboekContextToSpan(span, any<LogboekContext>(), any()) }
        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) }
    }

    @Test
    fun `doel-niet-in-config zet onbekend als foreign_operation_processor en markeert MISLUKT`() {
        // Dekt de warn-tak en het `<onbekend>`-fallback-pad wanneer config.downstreams()
        // de doel-key niet meer bevat (config-drift of removal-migratie).
        // PublicatieClaimVerwerker zet `<onbekend>` als span-attribute en DownstreamClient
        // retourneert ConfiguratieFout (non-herstelbaar) → markeerMislukt met null
        // volgendePoging.
        every { claimer.claimNuVerwerkbaar(maxBatch = 1) } returns listOf(claim)
        every { berichten.findByBerichtId(claim.berichtId) } returns bericht
        every { processingHandler.startSpan(any<String>(), any()) } returns span
        every { config.downstreams() } returns emptyMap()
        every { config.verwerkingsregisterPubliceren() } returns "https://register.example.com/x"
        every { cloudEventBuilder.bouw(bericht, claim.doel, any()) } returns event
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns
            DownstreamResultaat.ConfiguratieFout.voorVerzending("Downstream '${claim.doel.key}' niet geconfigureerd")
        justRun { claimer.markeerMislukt(any(), any(), any()) }

        val processorAttribuut = slot<String>()
        every {
            span.setAttribute("dpl.core.foreign_operation.processor", capture(processorAttribuut))
        } returns span

        verwerker.verwerkEenClaim()

        assertEquals("<onbekend>", processorAttribuut.captured)
        // ConfiguratieFout is non-herstelbaar → MISLUKT met volgendePoging=null.
        verify { claimer.markeerMislukt(claim.claimId, any(), null) }
    }

    @Test
    fun `doel-niet-in-config zet ERROR-status op de LDV-context`() {
        // Bij een onbekend doel staat de onmogelijkheid van de verstrekking al vast op
        // schrijfmoment; de logregel mag dan niet op UNSET (= geen fout) blijven staan.
        every { claimer.claimNuVerwerkbaar(maxBatch = 1) } returns listOf(claim)
        every { berichten.findByBerichtId(claim.berichtId) } returns bericht
        every { processingHandler.startSpan(any<String>(), any()) } returns span
        every { config.downstreams() } returns emptyMap()
        every { config.verwerkingsregisterPubliceren() } returns "https://register.example.com/x"
        every { cloudEventBuilder.bouw(bericht, claim.doel, any()) } returns event
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns
            DownstreamResultaat.ConfiguratieFout.voorVerzending("Downstream '${claim.doel.key}' niet geconfigureerd")
        justRun { claimer.markeerMislukt(any(), any(), any()) }

        val ldvContextSlot = slot<LogboekContext>()
        every { processingHandler.addLogboekContextToSpan(span, capture(ldvContextSlot), any()) } returns logregels

        verwerker.verwerkEenClaim()

        assertEquals(StatusCode.ERROR, ldvContextSlot.captured.status)
    }

    @Test
    fun `maxPogingen wordt per-downstream geresolved op claim doel, niet van een ander doel`() {
        // Borgt per-doel-resolutie: aanmeld heeft maxPogingen=1, notificatie=5. De claim
        // is voor aanmeld en faalt herstelbaar (NetwerkFout). pogingenNaFout=1 >= aanmeld.max
        // → terminal MISLUKT (volgendePoging=null). Zou de verwerker per ongeluk notificatie's
        // max=5 pakken, dan was er een retry gepland (volgendePoging != null) en faalt dit.
        every { claimer.claimNuVerwerkbaar(maxBatch = 1) } returns listOf(claim)
        every { berichten.findByBerichtId(claim.berichtId) } returns bericht
        every { processingHandler.startSpan(any<String>(), any()) } returns span
        every { config.downstreams() } returns mapOf(
            "aanmeld" to DownstreamStub("http://localhost:1/events", max = 1),
            "notificatie" to DownstreamStub("http://localhost:2/events", max = 5),
        )
        every { config.verwerkingsregisterPubliceren() } returns "https://register.example.com/x"
        every { cloudEventBuilder.bouw(bericht, claim.doel, any()) } returns event
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns
            DownstreamResultaat.NetwerkFout.geenVerbinding("transient")
        every { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) } returns emptyList()
        justRun { claimer.markeerMislukt(any(), any(), any()) }

        verwerker.verwerkEenClaim()

        // Terminal: volgendePoging == null omdat aanmeld.maxPogingen=1 is bereikt.
        verify { claimer.markeerMislukt(claim.claimId, any(), null) }
    }

    @Test
    fun `de logregel is bevestigd voordat er geleverd wordt`() {
        // Bevestigen na de levering zou betekenen dat een rollback op een LDV-fout een
        // al verstuurd CloudEvent opnieuw laat versturen.
        stubClaimMetBericht()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns DownstreamResultaat.Geslaagd
        justRun { claimer.markeerGeslaagd(claim.claimId, any()) }

        verwerker.verwerkEenClaim()

        verifyOrder {
            processingHandler.addLogboekContextToSpan(span, any<LogboekContext>(), any())
            span.end()
            processingHandler.enforceWriteAcknowledgement(true)
            downstreamClient.lever(claim.doel, event)
        }
    }

    @Test
    fun `een LDV-schrijffout verhindert de levering`() {
        stubClaimMetBericht()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        every {
            processingHandler.enforceWriteAcknowledgement(any())
        } throws LogboekWriteException("Logregel kon niet in het Logboek worden opgeslagen")

        assertThrows<LogboekWriteException> { verwerker.verwerkEenClaim() }

        verify { downstreamClient wasNot Called }
        verify(exactly = 0) { claimer.markeerGeslaagd(any(), any()) }
    }

    @Test
    fun `elke fout uit addLogboekContextToSpan propageert en de span eindigt alsnog`() {
        // Er is geen swallow meer: een fout hier betekent dat het logboek niet gevuld is,
        // en dan mag er niet geleverd worden.
        stubClaimMetBericht()
        every {
            processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any())
        } throws IllegalStateException("ldv stuk")
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        assertThrows<IllegalStateException> { verwerker.verwerkEenClaim() }

        verify { span.end() }
        verify { downstreamClient wasNot Called }
        // De recorder is thread-gebonden: zonder consumptie op déze uitgang erft de
        // volgende claim op dezelfde thread de schrijffout. `false` omdat er al een fout
        // propageert die niet gemaskeerd mag worden.
        verify { processingHandler.enforceWriteAcknowledgement(false) }
    }

    @ParameterizedTest
    @MethodSource("nietVerzondenLeveringen")
    fun `een levering die de afnemer zeker niet bereikte krijgt een ERROR-child zonder de reden`(
        resultaat: DownstreamResultaat.Mislukt,
        verwachteBeschrijving: String,
    ) {
        stubClaimMetBericht()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns resultaat
        justRun { claimer.markeerMislukt(any(), any(), any()) }
        val fout = slot<Throwable>()
        every { processingHandler.recordFailedOutcome(logregels, capture(fout)) } returns emptyList()

        verwerker.verwerkEenClaim()

        assertEquals(verwachteBeschrijving, fout.captured.message)
        assertFalse(
            fout.captured.message.orEmpty().contains("999993653"),
            "de reden kan tekst van de afnemer bevatten en hoort niet op een rij met de betrokkene",
        )

        verifyOrder {
            downstreamClient.lever(claim.doel, event)
            processingHandler.recordFailedOutcome(logregels, any())
            claimer.markeerMislukt(claim.claimId, any(), any())
        }
    }

    @ParameterizedTest
    @MethodSource("onzekereLeveringen")
    fun `een levering die de afnemer mogelijk bereikte krijgt geen ERROR-child`(resultaat: DownstreamResultaat.Mislukt) {
        // De afnemer kan het bericht hebben; "niet verstrekt" zou dan onder-rapportage zijn.
        // Een verstrekking te veel in het logboek is de toegestane kant.
        stubClaimMetBericht()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns resultaat
        justRun { claimer.markeerMislukt(any(), any(), any()) }

        verwerker.verwerkEenClaim()

        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) }
        verify { claimer.markeerMislukt(claim.claimId, any(), any()) }
    }

    @Test
    fun `ook een Error bij het schrijven van de logregel wordt niet door een schrijffout gemaskeerd`() {
        stubClaimMetBericht()
        every {
            processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any())
        } throws StackOverflowError()
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        assertThrows<StackOverflowError> { verwerker.verwerkEenClaim() }

        verify { span.end() }
        verify { processingHandler.enforceWriteAcknowledgement(false) }
        verify(exactly = 0) { processingHandler.enforceWriteAcknowledgement(true) }
    }

    @Test
    fun `een fout uit de levering zelf krijgt geen ERROR-child en propageert`() {
        // Het contract zegt dat lever niet gooit; doet het dat toch, dan is onbekend of er
        // iets verstuurd is. De rollback laat de claim openstaan voor de volgende ronde.
        stubClaimMetBericht()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } throws IllegalStateException("onverwacht")

        assertThrows<IllegalStateException> { verwerker.verwerkEenClaim() }

        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) }
        verify(exactly = 0) { claimer.markeerMislukt(any(), any(), any()) }
    }

    @Test
    fun `een falende uitkomst-logregel laat de claim-afhandeling ongemoeid`() {
        // MislukteUitkomst vangt ook een fout uit de wrapper zelf; de claim moet MISLUKT
        // worden zodat de retry doorgaat.
        stubClaimMetBericht()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns
            DownstreamResultaat.NetwerkFout.geenVerbinding("geweigerd")
        every { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) } throws
            NoSuchMethodError("recordFailedOutcome")
        justRun { claimer.markeerMislukt(any(), any(), any()) }

        verwerker.verwerkEenClaim()

        verify { claimer.markeerMislukt(claim.claimId, any(), any()) }
    }

    @Test
    fun `een geslaagde levering schrijft geen uitkomst-logregel`() {
        stubClaimMetBericht()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns DownstreamResultaat.Geslaagd
        justRun { claimer.markeerGeslaagd(claim.claimId, any()) }

        verwerker.verwerkEenClaim()

        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) }
    }

    @Test
    fun `een onbekend doel krijgt geen ERROR-child, de logregel zelf staat al op ERROR`() {
        every { claimer.claimNuVerwerkbaar(maxBatch = 1) } returns listOf(claim)
        every { berichten.findByBerichtId(claim.berichtId) } returns bericht
        every { processingHandler.startSpan(any<String>(), any()) } returns span
        every { config.downstreams() } returns emptyMap()
        every { config.verwerkingsregisterPubliceren() } returns "https://register.example.com/x"
        every { cloudEventBuilder.bouw(bericht, claim.doel, any()) } returns event
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns
            DownstreamResultaat.ConfiguratieFout.voorVerzending("Downstream '${claim.doel.key}' niet geconfigureerd")
        justRun { claimer.markeerMislukt(any(), any(), any()) }

        verwerker.verwerkEenClaim()

        verify(exactly = 0) { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) }
    }

    @Test
    fun `een opbouwfout maakt de claim terminaal en krijgt een ERROR-child zonder message`() {
        // Een opbouwfout herhaalt zich bij elke poging; doorgooien zou de transactie terugdraaien
        // en elke pollronde een nieuwe logregel met ERROR-child opleveren.
        stubClaimMetBericht()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { cloudEventBuilder.bouw(bericht, claim.doel, any()) } throws
            IllegalStateException("inhoud voor 999993653 niet te serialiseren")
        val fout = slot<Throwable>()
        every { processingHandler.recordFailedOutcome(logregels, capture(fout)) } returns emptyList()
        val reden = slot<String>()
        justRun { claimer.markeerMislukt(claim.claimId, capture(reden), null) }

        verwerker.verwerkEenClaim()

        assertEquals("SerialisatieFout", fout.captured.message)
        // De reden gaat de outbox en de applicatielog in; de message van de fout hoort daar niet.
        assertEquals("CloudEvent niet op te bouwen", reden.captured)
        verify { downstreamClient wasNot Called }
    }

    @Test
    fun `een Error bij het opbouwen krijgt een ERROR-child en gaat door`() {
        stubClaimMetBericht()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { cloudEventBuilder.bouw(bericht, claim.doel, any()) } throws StackOverflowError()
        val fout = slot<Throwable>()
        every { processingHandler.recordFailedOutcome(logregels, capture(fout)) } returns emptyList()

        assertThrows<StackOverflowError> { verwerker.verwerkEenClaim() }

        assertEquals(StackOverflowError::class.java.name, fout.captured.message)
        verify { downstreamClient wasNot Called }
    }

    @Test
    fun `een verloren uitkomst noemt claim, bericht en doel, maar niet de ontvanger`() {
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
            stubClaimMetBericht()
            every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
            justRun { processingHandler.enforceWriteAcknowledgement(any()) }
            every { downstreamClient.lever(claim.doel, event) } returns
                DownstreamResultaat.NetwerkFout.geenVerbinding("geweigerd")
            every { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) } returns logregels
            justRun { claimer.markeerMislukt(any(), any(), any()) }

            verwerker.verwerkEenClaim()
        } finally {
            logger.removeHandler(handler)
        }

        val melding = records.single { it.level == Level.SEVERE }.message
        assertTrue(melding.contains(claim.berichtId.toString()), melding)
        assertTrue(melding.contains("doel=${claim.doel.key}"), melding)
        assertTrue(melding.contains("claimId=${claim.claimId}"), melding)
        assertFalse(melding.contains("999993653"), "geen BSN in de applicatielog — was: $melding")
    }

    @Test
    fun `een verloren uitkomst-logregel laat de claim-afhandeling ongemoeid`() {
        // De uitkomst ging verloren (onder-rapportage); dat meldt MislukteUitkomst, maar
        // de claim moet gewoon MISLUKT worden zodat de retry doorgaat.
        stubClaimMetBericht()
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns DownstreamResultaat.Timeout.bijVerbinden("traag")
        every { processingHandler.recordFailedOutcome(any<Collection<Logregel>>(), any()) } returns logregels
        justRun { claimer.markeerMislukt(any(), any(), any()) }

        verwerker.verwerkEenClaim()

        verify { claimer.markeerMislukt(claim.claimId, any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 4])
    fun `elke poging draagt haar volgnummer, zodat pogingen voor een verstrekking herkenbaar zijn`(eerderePogingen: Int) {
        stubClaimMetBericht()
        every { claimer.claimNuVerwerkbaar(maxBatch = 1) } returns listOf(claim.copy(pogingen = eerderePogingen))
        every { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) } returns logregels
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns DownstreamResultaat.Geslaagd
        justRun { claimer.markeerGeslaagd(claim.claimId, any()) }

        verwerker.verwerkEenClaim()

        verify { span.setAttribute("publicatie.poging", eerderePogingen + 1L) }
    }

    companion object {
        @JvmStatic
        fun nietVerzondenLeveringen(): List<Arguments> = listOf(
            Arguments.of(DownstreamResultaat.Timeout.bijVerbinden("connect ontvanger 999993653"), "Timeout"),
            Arguments.of(DownstreamResultaat.NetwerkFout.geenVerbinding("ontvanger 999993653"), "NetwerkFout"),
            Arguments.of(DownstreamResultaat.SerialisatieFout.voorVerzending("ontvanger 999993653"), "SerialisatieFout"),
            // Een geconfigureerd doel met een ongeldige URL of TLS-handshake-fout; anders dan
            // een onbekend doel staat de logregel dan nog op UNSET.
            Arguments.of(DownstreamResultaat.ConfiguratieFout.voorVerzending("TLS-handshake ontvanger 999993653"), "ConfiguratieFout"),
        )

        @JvmStatic
        fun onzekereLeveringen(): List<DownstreamResultaat.Mislukt> = listOf(
            DownstreamResultaat.HttpFout(503, null, "ontvanger 999993653 onbekend"),
            DownstreamResultaat.HttpFout(400, null, "ontvanger 999993653 onbekend"),
            DownstreamResultaat.Timeout.bijLezen("read-timeout"),
            DownstreamResultaat.NetwerkFout.onderweg("connection reset"),
        )
    }
}
