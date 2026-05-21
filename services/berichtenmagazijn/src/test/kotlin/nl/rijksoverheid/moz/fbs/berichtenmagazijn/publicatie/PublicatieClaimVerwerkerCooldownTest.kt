package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.opentelemetry.api.trace.Span
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Borgt het log-storm-suppressie-contract van [LogStormLimiter] in
 * [PublicatieClaimVerwerker]:
 *
 * - Twee opeenvolgende `verwerkEenClaim()`-aanroepen met `config.downstreams()`
 *   die het doel mist mogen samen MAAR ÉÉN warn-regel produceren.
 * - Na `ONBEKEND_DOEL_WARN_COOLDOWN` mag een derde aanroep weer een warn loggen
 *   (cooldown reset werkt, geen permanente onderdrukking).
 *
 * Een refactor die de limiter weghaalt of de `if`-conditie inverteert breekt
 * deze pin — exact wat een test moet doen om silent regression te voorkomen.
 */
class PublicatieClaimVerwerkerCooldownTest {

    /** Mutable clock die we tussen aanroepen verschuiven om de cooldown te triggeren. */
    private class MutableClock(start: Instant) : Clock() {
        var now: Instant = start
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
    }

    private val claimer = mockk<PublicatieClaimer>()
    private val berichten = mockk<BerichtRepository>()
    private val cloudEventBuilder = mockk<CloudEventBuilder>()
    private val downstreamClient = mockk<DownstreamClient>()
    private val config = mockk<PublicatieConfig>()
    private val processingHandler = mockk<ProcessingHandler>()
    private val span = mockk<Span>(relaxed = true)
    private val clock = MutableClock(Instant.parse("2026-05-12T10:00:00Z"))

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

    private val julLogger: Logger = Logger.getLogger(PublicatieClaimVerwerker::class.java.name)
    private val records = mutableListOf<LogRecord>()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records.add(record)
        }
        override fun flush() {}
        override fun close() {}
    }

    @BeforeEach
    fun installHandler() {
        julLogger.addHandler(handler)
        julLogger.level = Level.ALL
        records.clear()
        stubAlleAanroepen()
    }

    @AfterEach
    fun removeHandler() {
        julLogger.removeHandler(handler)
    }

    private fun stubAlleAanroepen() {
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
    }

    private fun aantalOnbekendDoelWarns(): Int =
        records.count {
            it.level == Level.WARNING &&
                (it.message?.contains("niet (meer) in config.downstreams") == true)
        }

    @Test
    fun `tweede aanroep binnen cooldown-venster onderdrukt warn`() {
        verwerker.verwerkEenClaim()
        verwerker.verwerkEenClaim()

        assertEquals(
            1,
            aantalOnbekendDoelWarns(),
            "verwacht exact 1 warn — cooldown moet 2e suppress'en",
        )
    }

    @Test
    fun `aanroep na cooldown-venster mag opnieuw warnen`() {
        verwerker.verwerkEenClaim()

        // Verschuif klok voorbij cooldown-venster (5 min + 1 sec marge).
        clock.now = clock.now.plus(PublicatieClaimVerwerker.ONBEKEND_DOEL_WARN_COOLDOWN)
            .plusSeconds(1)

        verwerker.verwerkEenClaim()

        assertEquals(
            2,
            aantalOnbekendDoelWarns(),
            "verwacht 2 warns — na cooldown-reset mag de tweede emit'en",
        )
    }
}
