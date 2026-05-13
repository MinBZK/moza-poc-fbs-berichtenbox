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
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Oin
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
 * Borgt de PII-discipline van de IAE-finally-tak in [AanleverResource]:
 *
 *  1. `pendingFailure.message` mag NIET in de IAE-log-regel terechtkomen
 *     (FoutBeschrijving.saneer dekt geen niet-numerieke PII).
 *  2. Het `cause`-veld (categorie + cause-type van de oorspronkelijke
 *     exception) MOET aanwezig zijn als 2e correlatie-handvat.
 *  3. Een eigen `ldvErrorId` (UUID) moet in de log staan voor cross-
 *     correlatie met de ProblemExceptionMapper-log-regel die zijn eigen
 *     errorId genereert voor dezelfde request.
 *
 * Trigger-pad: `addLogboekContextToSpan` gooit `IllegalArgumentException` in
 * de outer-finally; dat triggert de inner `catch (IAE)`-tak. Die ge-IAE'd
 * pad bestaat omdat de oorspronkelijke `pendingFailure` (uit de outer-catch)
 * mogelijk PII bevat in `message` — een refactor die `pendingFailure.message`
 * weer in de log opneemt zou hier gevangen worden.
 */
class AanleverResourcePendingFailureLogTest {

    private val opslagService = mockk<BerichtOpslagService>()
    private val logboekContext = LogboekContext()
    private val processingHandler = mockk<ProcessingHandler>()
    private val publicatieConfig = mockk<PublicatieConfig>()
    private val span = mockk<Span>(relaxed = true)
    private val uriInfo = mockk<UriInfo>().apply {
        every { baseUriBuilder } answers { UriBuilder.fromUri(URI.create("http://localhost/")) }
    }
    private val clock = Clock.fixed(Instant.parse("2026-05-13T10:00:00Z"), ZoneId.of("UTC"))
    private val httpHeaders = mockk<HttpHeaders>().apply {
        every { getHeaderString("traceparent") } returns null
        every { getHeaderString("traceparent-processor") } returns null
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
        // Default OK-pad voor finally; afzonderlijke tests overschrijven om IAE te triggeren.
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }
    }

    @AfterEach
    fun teardown() {
        julLogger.removeHandler(handler)
    }

    @Test
    fun `IAE-log laat pendingFailure-message NIET zien (PII-protectie consistent met mappers)`() {
        // Arrange: oorspronkelijke business-failure met PII-message. Forceer dat
        // de outer try faalt door opslagService te laten gooien.
        val piiMessage = "Upstream voor Jan de Vries (jan@example.nl) en BSN 999993653 mislukt"
        every {
            opslagService.opslaanBericht(
                afzender = any(),
                ontvangerType = any(),
                ontvangerWaarde = any(),
                onderwerp = any(),
                inhoud = any(),
                publicatieDatum = any(),
            )
        } throws RuntimeException(piiMessage)
        // Forceer dat addLogboekContextToSpan in finally een IAE gooit
        // (configuratie-fout van ProcessingHandler), zodat de inner catch hit.
        every {
            processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>())
        } throws IllegalArgumentException("processingActivityId leeg of ongeldig")

        // Act: oorspronkelijke RuntimeException komt door (re-throw in outer catch)
        assertThrows(RuntimeException::class.java) {
            resource.leverBerichtAan(request)
        }

        // Assert: er is een SEVERE-log-regel voor de IAE-finally-tak
        val severe = records.filter { it.level == Level.SEVERE }
        assertEquals(1, severe.size, "verwacht 1 SEVERE-regel voor IAE-finally — gevonden: $severe")
        val rec = severe.first()
        // errorf eager-formats; rec.message bevat al-gerenderde regel
        val rendered = rec.message
        // PII-discipline: oorspronkelijke message-tekst mag niet door
        assertFalse(
            rendered.contains("Jan de Vries"),
            "naam-PII van pendingFailure mag NIET in IAE-log — gevonden: $rendered",
        )
        assertFalse(
            rendered.contains("jan@example.nl"),
            "e-mail PII van pendingFailure mag NIET in IAE-log — gevonden: $rendered",
        )
        assertFalse(
            rendered.contains("999993653"),
            "BSN van pendingFailure mag NIET in IAE-log — gevonden: $rendered",
        )
        assertFalse(
            rendered.contains(piiMessage),
            "raw pendingFailure.message mag NIET in IAE-log — gevonden: $rendered",
        )
    }

    @Test
    fun `IAE-log bevat cause-categorie van pendingFailure als correlatie-handvat`() {
        // Borgt dat het 2e correlatie-handvat (categorie + cause-type) WEL
        // mee-gaat — zonder dit verliest support de link tussen IAE-finally
        // en oorspronkelijke fout-categorie.
        every {
            opslagService.opslaanBericht(
                afzender = any(),
                ontvangerType = any(),
                ontvangerWaarde = any(),
                onderwerp = any(),
                inhoud = any(),
                publicatieDatum = any(),
            )
        } throws IllegalStateException("interne staat ongeldig", RuntimeException("DB-fout cause"))
        every {
            processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>())
        } throws IllegalArgumentException("processingActivityId leeg of ongeldig")

        assertThrows(IllegalStateException::class.java) {
            resource.leverBerichtAan(request)
        }

        val rec = records.filter { it.level == Level.SEVERE }.first()
        val rendered = rec.message
        assertTrue(
            rendered.contains("categorie=IllegalStateException"),
            "fout-categorie van pendingFailure vereist — gevonden: $rendered",
        )
        assertTrue(
            rendered.contains("cause=RuntimeException"),
            "cause-type van pendingFailure vereist — gevonden: $rendered",
        )
    }

    @Test
    fun `IAE-log bevat eigen ldvErrorId voor cross-correlatie met ProblemExceptionMapper`() {
        // Round 12 L3: ProblemExceptionMapper genereert eigen errorId voor de
        // re-thrown business-exception. Deze IAE-log moet eigen errorId hebben
        // zodat een operator beide log-regels (LDV-koppelen-faal + Server error)
        // kan koppelen, ook als de pipeline throwables filtert.
        every {
            opslagService.opslaanBericht(
                afzender = any(),
                ontvangerType = any(),
                ontvangerWaarde = any(),
                onderwerp = any(),
                inhoud = any(),
                publicatieDatum = any(),
            )
        } throws RuntimeException("origineel")
        every {
            processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>())
        } throws IllegalArgumentException("ldv-config-fout")

        assertThrows(RuntimeException::class.java) {
            resource.leverBerichtAan(request)
        }

        val rec = records.filter { it.level == Level.SEVERE }.first()
        assertTrue(
            rec.message.contains("ldvErrorId="),
            "ldvErrorId-veld vereist voor cross-correlatie — gevonden: ${rec.message}",
        )
        // UUID-shape (vermijdt placeholder-strings als 'ldvErrorId=null')
        val patroon = Regex("ldvErrorId=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
        assertNotNull(
            patroon.find(rec.message),
            "ldvErrorId moet UUID-formaat hebben — gevonden: ${rec.message}",
        )
    }

}
