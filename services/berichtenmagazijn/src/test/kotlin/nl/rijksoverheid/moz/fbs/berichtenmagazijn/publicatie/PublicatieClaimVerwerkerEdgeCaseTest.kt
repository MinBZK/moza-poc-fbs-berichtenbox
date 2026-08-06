package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.mockk.Called
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekWriteException
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Borgt defensieve paden in [PublicatieClaimVerwerker]:
 *  1. **Duplicate-send venster**: downstream gaf 2xx, maar `markeerGeslaagd`
 *     gooit `IllegalStateException`. Verwerker moet ERROR-loggen en
 *     re-throwen zodat REQUIRES_NEW rolt; volgende pollronde retried met
 *     dezelfde UUIDv5-id (downstream dedupliceert).
 *  2. **Logregel-vóór-levering-volgorde**: de LDV-schrijfactie wordt bevestigd
 *     vóórdat het CloudEvent de deur uitgaat. Faalt de schrijfactie, dan mag er
 *     niet geleverd worden; faalt de levering, dan blijft de al bevestigde
 *     logregel onaangeroerd (die zit niet in de JTA-transactie).
 */
class PublicatieClaimVerwerkerEdgeCaseTest {

    private class DownstreamStub(private val u: String, private val max: Int = 3) : PublicatieConfig.Downstream {
        override fun url(): String = u
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
            onderwerp = "X", inhoud = "x",
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
        // faalroute raakt het logboek niet meer, alleen de claim-status.
        stubClaimMetBericht()
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns DownstreamResultaat.Geslaagd
        every { claimer.markeerGeslaagd(claim.claimId, any()) } throws
            IllegalStateException("delivery weg, contract gebroken")

        // Re-thrown zodat REQUIRES_NEW van caller rollbacked en volgende ronde retried.
        assertThrows<IllegalStateException> { verwerker.verwerkEenClaim() }
        verify { processingHandler.addLogboekContextToSpan(span, any<LogboekContext>(), any()) }
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
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns
            DownstreamResultaat.ConfiguratieFout("Downstream '${claim.doel.key}' niet geconfigureerd")
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
            DownstreamResultaat.ConfiguratieFout("Downstream '${claim.doel.key}' niet geconfigureerd")
        justRun { claimer.markeerMislukt(any(), any(), any()) }

        val ldvContextSlot = slot<LogboekContext>()
        justRun { processingHandler.addLogboekContextToSpan(span, capture(ldvContextSlot), any()) }

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
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }
        every { downstreamClient.lever(claim.doel, event) } returns
            DownstreamResultaat.NetwerkFout("transient")
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
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
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
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
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

        assertThrows<IllegalStateException> { verwerker.verwerkEenClaim() }

        verify { span.end() }
        verify { downstreamClient wasNot Called }
    }
}
