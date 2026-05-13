package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.opentelemetry.api.trace.Span
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.UriBuilder
import jakarta.ws.rs.core.UriInfo
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtAanleverenRequest
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Identificatienummer as IdentificatienummerDto
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bsn
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Borgt het log-storm-suppressie-contract voor de whitelist-rejection warn
 * in [AanleverResource]:
 *
 * - Twee opeenvolgende rejected requests binnen het cooldown-venster mogen
 *   samen MAAR ÉÉN warn-regel produceren.
 * - Na [AanleverResource.WHITELIST_REJECTION_COOLDOWN] mag een volgende
 *   rejection weer een warn loggen (cooldown-reset werkt).
 *
 * Zonder deze pin zou een refactor die de limiter weghaalt of `magEmitten`
 * invert slagen zonder test-falen — dat zou de log-volume DoS-mitigatie
 * stilletjes ongedaan maken op een ongeauthentiseerd endpoint.
 */
class AanleverResourceWhitelistCooldownTest {

    /** Klok die we tussen aanroepen verschuiven om de cooldown te triggeren. */
    private class MutableClock(start: Instant) : Clock() {
        var now: Instant = start
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
    }

    private val opslagService = mockk<BerichtOpslagService>()
    private val logboekContext = LogboekContext()
    private val processingHandler = mockk<ProcessingHandler>()
    private val publicatieConfig = mockk<PublicatieConfig>()
    private val span = mockk<Span>(relaxed = true)
    private val uriInfo = mockk<UriInfo>().apply {
        every { baseUriBuilder } answers { UriBuilder.fromUri(URI.create("http://localhost/")) }
    }
    private val clock = MutableClock(Instant.parse("2026-05-13T10:00:00Z"))
    private val httpHeaders = mockk<HttpHeaders>().apply {
        every { getHeaderString("traceparent") } returns
            "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
        // Niet-conforme processor-header die door whitelist wordt geweigerd.
        every { getHeaderString("traceparent-processor") } returns "evil\r\nlog inject"
        every { requestHeaders } returns MultivaluedHashMap()
    }

    private val resource = AanleverResource(
        opslagService = opslagService,
        logboekContext = logboekContext,
        processingHandler = processingHandler,
        publicatieConfig = publicatieConfig,
        clock = clock,
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
        publicatieDatum = Instant.parse("2026-05-13T10:00:00Z"),
    )

    private val julLogger: Logger = Logger.getLogger(AanleverResource::class.java.name)
    private val records = mutableListOf<LogRecord>()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records.add(record)
        }
        override fun flush() {}
        override fun close() {}
    }

    @BeforeEach
    fun setup() {
        julLogger.addHandler(handler)
        julLogger.level = Level.ALL
        records.clear()
        every { processingHandler.startSpan("aanleveren-bericht", null) } returns span
        every { publicatieConfig.verwerkingsregisterAanleveren() } returns "https://register.example.com/aanleveren"
        every {
            opslagService.opslaanBericht(
                afzender = any(),
                ontvangerType = any(),
                ontvangerWaarde = any(),
                onderwerp = any(),
                inhoud = any(),
                publicatieDatum = any(),
            )
        } returns gevalideerdBericht
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }
        every {
            span.setAttribute("dpl.core.foreign_operation.processor", any<String>())
        } returns span
    }

    @AfterEach
    fun teardown() {
        julLogger.removeHandler(handler)
    }

    private fun aantalRejectionWarns(): Int =
        records.count {
            it.level == Level.WARNING &&
                (it.message?.contains("whitelist-rejection") == true)
        }

    @Test
    fun `tweede rejection binnen cooldown wordt onderdrukt`() {
        resource.leverBerichtAan(request)
        resource.leverBerichtAan(request)

        assertEquals(
            1,
            aantalRejectionWarns(),
            "verwacht 1 warn — cooldown moet 2e onderdrukken (anders log-volume DoS)",
        )
    }

    @Test
    fun `na cooldown-venster mag opnieuw geweer worden`() {
        resource.leverBerichtAan(request)

        // Verschuif klok voorbij cooldown-venster (1 min + 1 sec marge).
        clock.now = clock.now.plus(AanleverResource.WHITELIST_REJECTION_COOLDOWN)
            .plusSeconds(1)

        resource.leverBerichtAan(request)

        assertEquals(
            2,
            aantalRejectionWarns(),
            "verwacht 2 warns — na cooldown-reset mag de tweede emit'en",
        )
    }

    @Test
    fun `legitieme traceparent-processor produceert GEEN whitelist-rejection warn`() {
        // Borgt dat de cooldown niet de discriminatie verbergt: een geldige
        // upstream-header (matcht regex) mag GEEN warn loggen, ook niet 1×.
        // Refactor die magEmitten onvoorwaardelijk aanroept zou hier falen.
        every { httpHeaders.getHeaderString("traceparent-processor") } returns
            "rijksoverheid.nl/ldv/v1.2"

        resource.leverBerichtAan(request)

        assertEquals(
            0,
            aantalRejectionWarns(),
            "legitieme header mag GEEN rejection-warn produceren — gevonden: ${records.map { it.message }}",
        )
    }
}
