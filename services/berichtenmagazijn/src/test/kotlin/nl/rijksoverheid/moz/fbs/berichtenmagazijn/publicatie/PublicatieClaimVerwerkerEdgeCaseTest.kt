package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Borgt twee defensieve paden in [PublicatieClaimVerwerker] die in eerdere
 * reviews als ongetest werden geflagd:
 *  1. **Duplicate-send venster**: downstream gaf 2xx, maar `markeerGeslaagd`
 *     gooit `IllegalStateException`. Verwerker moet ERROR-loggen en
 *     re-throwen zodat REQUIRES_NEW rolt; volgende pollronde retried met
 *     dezelfde UUIDv5-id (downstream dedupliceert).
 *  2. **LDV-handler-failure swallow**: `addLogboekContextToSpan` gooit; mag
 *     de transactie-commit niet ondermijnen — wordt in finally afgevangen
 *     en gelogd, geen propagatie.
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
        publicatiedatum = Instant.parse("2026-05-12T10:00:00Z"),
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
            publicatiedatum = bericht.publicatiedatum,
        ),
    )

    private fun stubBaseline() {
        every { claimer.claimNuVerwerkbaar(maxBatch = 1) } returns listOf(claim)
        every { berichten.findByBerichtId(claim.berichtId) } returns bericht
        every { processingHandler.startSpan(any<String>(), any()) } returns span
        every { config.downstreams() } returns mapOf("aanmeld" to DownstreamStub("http://localhost:1/events"))
        every { config.verwerkingsregisterPubliceren() } returns "https://register.example.com/x"
        every { cloudEventBuilder.bouw(bericht, claim.doel, any()) } returns event
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }
    }

    @Test
    fun `markeerGeslaagd faalt na 2xx = duplicate-send venster gelogd en herthrown`() {
        stubBaseline()
        every { downstreamClient.lever(claim.doel, event) } returns DownstreamResultaat.Geslaagd
        every { claimer.markeerGeslaagd(claim.claimId, any()) } throws
            IllegalStateException("delivery weg, contract gebroken")

        // Re-thrown zodat REQUIRES_NEW van caller rollbacked en volgende ronde retried.
        assertThrows(IllegalStateException::class.java) { verwerker.verwerkEenClaim() }
        // LDV-context ondanks fout alsnog gekoppeld.
        verify { processingHandler.addLogboekContextToSpan(span, any<LogboekContext>()) }
    }

    @Test
    fun `LDV-config-fout wordt geslikt en status-write blijft staan`() {
        stubBaseline()
        every { downstreamClient.lever(claim.doel, event) } returns DownstreamResultaat.Geslaagd
        justRun { claimer.markeerGeslaagd(claim.claimId, any()) }
        // Round 5: catch is genarrowed van RuntimeException naar IllegalArgumentException
        // — dat is wat ProcessingHandler zelf gooit op niet-URI/leeg processingActivityId
        // (config-state-fout). Andere RuntimeExceptions wijzen op programmeerfouten en
        // moeten WÉL REQUIRES_NEW rollen.
        every {
            processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>())
        } throws IllegalArgumentException("processingActivityId moet absolute URI zijn")

        // Géén exception verwacht — LDV-config-fout zou anders REQUIRES_NEW rolen
        // en duplicate-send forceren bij volgende ronde.
        verwerker.verwerkEenClaim()

        // markeerGeslaagd is wél aangeroepen (status-write doorgegaan).
        verify { claimer.markeerGeslaagd(claim.claimId, any()) }
        // Round 6 H2: span.end() draait OOK na geslikte LDV-fout (eigen finally).
        verify { span.end() }
    }

    @Test
    fun `doel-niet-in-config zet onbekend als foreign_operation_processor en markeert MISLUKT`() {
        // M5 test-gap: dekt de warn-tak en het `<onbekend>`-fallback-pad
        // wanneer config.downstreams() de doel-key niet meer bevat (config-drift
        // of removal-migratie). PublicatieClaimVerwerker zet `<onbekend>` als
        // span-attribute en DownstreamClient retourneert ConfiguratieFout
        // (non-herstelbaar) → markeerMislukt met null volgendePoging.
        every { claimer.claimNuVerwerkbaar(maxBatch = 1) } returns listOf(claim)
        every { berichten.findByBerichtId(claim.berichtId) } returns bericht
        every { processingHandler.startSpan(any<String>(), any()) } returns span
        every { config.downstreams() } returns emptyMap()
        every { config.verwerkingsregisterPubliceren() } returns "https://register.example.com/x"
        every { cloudEventBuilder.bouw(bericht, claim.doel, any()) } returns event
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }
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
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }
        every { downstreamClient.lever(claim.doel, event) } returns
            DownstreamResultaat.NetwerkFout("transient")
        justRun { claimer.markeerMislukt(any(), any(), any()) }

        verwerker.verwerkEenClaim()

        // Terminal: volgendePoging == null omdat aanmeld.maxPogingen=1 is bereikt.
        verify { claimer.markeerMislukt(claim.claimId, any(), null) }
    }

    @Test
    fun `niet-IAE uit ProcessingHandler propageert WEL en span end() draait alsnog`() {
        // Round 6 H2: Borgt het catch-narrowing-contract. Een RuntimeException
        // (hier IllegalStateException) uit `addLogboekContextToSpan` MAG niet
        // geslikt worden door de finally — dat zou programmeerfouten verbergen.
        // Tegelijk MOET span.end() in zijn eigen finally draaien (anders span-leak).
        // Een toekomstige refactor die catch broadens naar `RuntimeException`
        // zou markeerGeslaagd-status laten staan terwijl REQUIRES_NEW had moeten rollen
        // → duplicate-send risico. Deze test pint dat contract vast.
        stubBaseline()
        every { downstreamClient.lever(claim.doel, event) } returns DownstreamResultaat.Geslaagd
        justRun { claimer.markeerGeslaagd(claim.claimId, any()) }
        every {
            processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>())
        } throws IllegalStateException("LDV-lib state corruptie")

        assertThrows(IllegalStateException::class.java) { verwerker.verwerkEenClaim() }
        // span.end() draaide ondanks doorgegooide fout (eigen finally rond addLogboekContextToSpan).
        verify { span.end() }
    }
}
