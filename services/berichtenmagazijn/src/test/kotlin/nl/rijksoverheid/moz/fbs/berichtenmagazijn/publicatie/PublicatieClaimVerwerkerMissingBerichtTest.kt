package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verifyOrder
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekWriteException
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Borgt de defensieve `bericht == null`-tak in [PublicatieClaimVerwerker]:
 * CASCADE op `publicatie_deliveries.bericht_id` maakt deze tak onbereikbaar
 * in productie, maar handmatige DB-mutaties of toekomstige soft-delete kunnen
 * hem activeren. Test verifieert dat zo'n claim als terminal `MISLUKT` wordt
 * gemarkeerd (geen retry zonder bron-data) i.p.v. eindeloos opnieuw geclaimd,
 * en dat het eigen span-/logregel-beheer van dit pad klopt.
 *
 * Geen `relaxed = true`: MockK genereert dan via reflectie sample-instanties
 * voor default-returntypes, en raakt onze [Publicatiedoel]-`init`-validatie
 * met willekeurige strings.
 */
class PublicatieClaimVerwerkerMissingBerichtTest {

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

    private val claim = PublicatieClaim(
        claimId = 1L,
        berichtId = UUID.randomUUID(),
        doel = Publicatiedoel("aanmeld"),
        pogingen = 0,
    )

    private fun stubOntbrekendBericht() {
        every { claimer.claimNuVerwerkbaar(maxBatch = 1) } returns listOf(claim)
        every { berichten.findByBerichtId(claim.berichtId) } returns null
        every { processingHandler.startSpan(any<String>(), any()) } returns span
        every { config.verwerkingsregisterPubliceren() } returns "https://register.example.com/x"
    }

    @Test
    fun `bericht weg = markeerMislukt zonder volgendePoging (terminal MISLUKT)`() {
        stubOntbrekendBericht()

        // Capture markeerMislukt-args zodat we ze direct kunnen verifiëren —
        // omzeilt MockK's `verify { ... }` reflection-pad op niet-relaxed mock.
        val gevangenFout = AtomicReference<String?>()
        val gevangenVolgendePoging = AtomicReference<Instant?>()
        every {
            claimer.markeerMislukt(
                claimId = claim.claimId,
                fout = any(),
                volgendePoging = any(),
            )
        } answers {
            gevangenFout.set(arg<String>(1))
            gevangenVolgendePoging.set(arg<Instant?>(2))
        }
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        verwerker.verwerkEenClaim()

        assertEquals("Bericht niet gevonden", gevangenFout.get())
        assertNull(gevangenVolgendePoging.get(), "terminal MISLUKT vereist null volgendePoging")
    }

    @Test
    fun `bericht weg = markeerMislukt vóór de logregel, dan span end, dan enforceWriteAcknowledgement`() {
        stubOntbrekendBericht()
        justRun { claimer.markeerMislukt(any(), any(), any()) }
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        verwerker.verwerkEenClaim()

        verifyOrder {
            claimer.markeerMislukt(claim.claimId, any(), null)
            processingHandler.addLogboekContextToSpan(span, any<LogboekContext>(), any())
            span.end()
            processingHandler.enforceWriteAcknowledgement(true)
        }
    }

    @Test
    fun `bericht weg = LDV-context krijgt status ERROR`() {
        stubOntbrekendBericht()
        justRun { claimer.markeerMislukt(any(), any(), any()) }
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        val ldvContextSlot = slot<LogboekContext>()
        justRun { processingHandler.addLogboekContextToSpan(span, capture(ldvContextSlot), any()) }

        verwerker.verwerkEenClaim()

        assertEquals(StatusCode.ERROR, ldvContextSlot.captured.status)
    }

    @Test
    fun `bericht weg = een LDV-schrijffout propageert`() {
        stubOntbrekendBericht()
        justRun { claimer.markeerMislukt(any(), any(), any()) }
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
        every {
            processingHandler.enforceWriteAcknowledgement(any())
        } throws LogboekWriteException("Logregel kon niet in het Logboek worden opgeslagen")

        assertThrows<LogboekWriteException> { verwerker.verwerkEenClaim() }
    }
}
