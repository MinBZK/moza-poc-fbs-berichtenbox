package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Borgt twee defensieve paden in [AanleverResource] die in Round 5 als test-gap
 * werden geflagd:
 *  1. **LDV-fout swallow**: `addLogboekContextToSpan` gooit (bv. lege URI) →
 *     mag de response (HTTP 201) NIET breken. Anders zou een config-fout in
 *     LDV elke aanlever-call laten falen met 500.
 *  2. **dataSubjectType correlatie-parity**: na succes-pad moet het
 *     `dpl.core.data_subject_id_type`-veld de concrete type-naam (BSN/RSIN/KVK)
 *     bevatten — niet de relationele rol "ontvanger". Anders correleert
 *     LDV-record met dat van [PublicatieClaimVerwerker] niet meer.
 *
 * Geen `@QuarkusTest` nodig — we instantiëren de resource direct met mocks
 * (analoog aan [BerichtOpslagServiceTest]); CDI/proxy-laag is niet onder test.
 */
class AanleverResourceLdvSwallowTest {

    private val opslagService = mockk<BerichtOpslagService>()
    private val logboekContext = LogboekContext()
    private val processingHandler = mockk<ProcessingHandler>()
    private val publicatieConfig = mockk<PublicatieConfig>()
    private val span = mockk<Span>(relaxed = true)
    private val uriInfo = mockk<UriInfo>().apply {
        every { baseUriBuilder } answers { UriBuilder.fromUri(URI.create("http://localhost/")) }
    }
    private val httpHeaders = mockk<HttpHeaders>().apply {
        every { getHeaderString("traceparent") } returns null
        every { requestHeaders } returns MultivaluedHashMap()
    }

    // Elke test krijgt een verse resource zodat de interne LogStormLimiter
    // (whitelist-rejection cooldown) niet tussen tests in lekt; cooldown wordt
    // anders door de eerste test "verbruikt" en latere whitelist-tests zien
    // geen warn meer.
    private val resource: AanleverResource
        get() = AanleverResource(
            opslagService = opslagService,
            logboekContext = logboekContext,
            processingHandler = processingHandler,
            publicatieConfig = publicatieConfig,
            clock = java.time.Clock.systemUTC(),
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

    private fun stubBaseline() {
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
    }

    @Test
    fun `addLogboekContextToSpan-fout breekt response NIET`() {
        stubBaseline()
        // Simuleer config-fout in LDV-stack: niet-URI processingActivityId
        // → ProcessingHandler.addLogboekContextToSpan gooit IllegalArgumentException.
        // `any()`-matchers voor argumenten omdat de mutable LogboekContext anders
        // mockk's eq()-vergelijking faalt zodra de resource velden erop zet.
        every {
            processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>())
        } throws IllegalArgumentException("processingActivityId moet absolute URI zijn")

        val response = resource.leverBerichtAan(request)

        assertNotNull(response, "response moet 201-payload zijn ondanks LDV-fout")
        assertEquals(gevalideerdBericht.berichtId, response.berichtId)
        verify { span.end() }
    }

    @Test
    fun `dataSubjectType krijgt concrete type BSN in plaats van relationele rol`() {
        stubBaseline()
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }

        resource.leverBerichtAan(request)

        // Borgt parity met PublicatieClaimVerwerker; "ontvanger" zou LDV-correlatie
        // breken tussen aanleveren- en publiceren-records voor dezelfde subject.
        assertEquals("BSN", logboekContext.dataSubjectType)
        assertEquals("999993653", logboekContext.dataSubjectId)
    }

    @Test
    fun `IllegalStateException uit ProcessingHandler propageert WEL als 500 en span end() draait alsnog`() {
        stubBaseline()
        // Niet-LDV-config-fout (hier IllegalStateException simuleert lib-bug
        // of kapotte CDI-proxy) MAG niet stilletjes worden geslikt — anders
        // verbergt het echte programmeerfouten.
        every {
            processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>())
        } throws IllegalStateException("Span al ge-end()'d")

        // De resource catcht enkel IllegalArgumentException; IllegalStateException
        // moet doorvliegen.
        assertThrows(IllegalStateException::class.java) {
            resource.leverBerichtAan(request)
        }
        // Borgt H1-Round-6: span.end() in eigen finally draait OOK als
        // addLogboekContextToSpan een niet-IAE doorgooit. Zonder eigen finally
        // zou een refactor de span lekken naar OTel-exporter.
        verify { span.end() }
    }

    @Test
    fun `traceparent-processor met CRLF-injectie wordt naar lege string gemapt`() {
        stubBaseline()
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }
        every { httpHeaders.getHeaderString("traceparent") } returns
            "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
        every { httpHeaders.getHeaderString("traceparent-processor") } returns
            "vendor=evil\r\nlog inject"

        val attribuutWaarde = slot<String>()
        every {
            span.setAttribute("dpl.core.foreign_operation.processor", capture(attribuutWaarde))
        } returns span

        resource.leverBerichtAan(request)

        // CRLF + spatie matchen niet de whitelist → setAttribute met "" (geen poisoning).
        assertEquals("", attribuutWaarde.captured)
    }

    @Test
    fun `traceparent-processor met hex-only oversize wordt geweigerd`() {
        stubBaseline()
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }
        every { httpHeaders.getHeaderString("traceparent") } returns
            "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
        // 257 hex-chars: chars matchen klasse maar lengte > 256 → reject.
        every { httpHeaders.getHeaderString("traceparent-processor") } returns "a".repeat(257)

        val attribuutWaarde = slot<String>()
        every {
            span.setAttribute("dpl.core.foreign_operation.processor", capture(attribuutWaarde))
        } returns span

        resource.leverBerichtAan(request)

        // Lengte-overschrijding → setAttribute met "" (audit-DoS preventie).
        assertEquals("", attribuutWaarde.captured)
    }

    @Test
    fun `geldige W3C-vorm in traceparent-processor wordt doorgelaten`() {
        stubBaseline()
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }
        every { httpHeaders.getHeaderString("traceparent") } returns
            "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
        // Realistic vendor processor-id: alphanumerieken + slash + dot + dash.
        every { httpHeaders.getHeaderString("traceparent-processor") } returns
            "rijksoverheid.nl/ldv/v1.2"

        val attribuutWaarde = slot<String>()
        every {
            span.setAttribute("dpl.core.foreign_operation.processor", capture(attribuutWaarde))
        } returns span

        resource.leverBerichtAan(request)

        // Match → originele waarde doorgelaten naar OTel-attribute.
        assertEquals("rijksoverheid.nl/ldv/v1.2", attribuutWaarde.captured)
    }

    @Test
    fun `traceparent-processor van exact 256 chars wordt geaccepteerd (boundary)`() {
        // Boundary-pin: regex is `{1,256}`. Een off-by-one naar `{1,255}` zou
        // pas hier zichtbaar worden — andere tests gebruiken kortere of langere strings.
        stubBaseline()
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }
        every { httpHeaders.getHeaderString("traceparent") } returns
            "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
        every { httpHeaders.getHeaderString("traceparent-processor") } returns "a".repeat(256)

        val attribuutWaarde = slot<String>()
        every {
            span.setAttribute("dpl.core.foreign_operation.processor", capture(attribuutWaarde))
        } returns span

        resource.leverBerichtAan(request)

        assertEquals(256, attribuutWaarde.captured.length, "256 chars = boundary-OK")
    }

    @Test
    fun `whitelist-rejection produceert warn-log met gesaneerde snippet`() {
        // M5: signal voor ops moet via warn-niveau zichtbaar zijn (niet debug).
        stubBaseline()
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }
        every { httpHeaders.getHeaderString("traceparent") } returns
            "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
        every { httpHeaders.getHeaderString("traceparent-processor") } returns
            "evil\r\nBSN=999993653 inject"
        every {
            span.setAttribute("dpl.core.foreign_operation.processor", any<String>())
        } returns span

        val julLogger: Logger = Logger.getLogger(AanleverResource::class.java.name)
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records.add(record)
            }
            override fun flush() {}
            override fun close() {}
        }
        julLogger.addHandler(handler)
        julLogger.level = Level.ALL
        try {
            resource.leverBerichtAan(request)
        } finally {
            julLogger.removeHandler(handler)
        }

        val warnRecords = records.filter { it.level == Level.WARNING }
        assertTrue(warnRecords.isNotEmpty(), "verwacht warn-log voor whitelist-rejection")
        val warnFormatted = warnRecords.first().let { rec ->
            rec.parameters?.let { String.format(rec.message, *it) } ?: rec.message
        }
        assertTrue(
            warnFormatted.contains("whitelist-rejection"),
            "warn-message moet rejection benoemen — gevonden: $warnFormatted",
        )
        // Saneer-discipline: BSN-cijferreeks moet [REDACTED] zijn; CRLF moet geen
        // nieuwe log-regel kunnen smokkelen.
        assertTrue(
            !warnFormatted.contains("999993653"),
            "BSN mag niet in warn-log lekken — gevonden: $warnFormatted",
        )
        assertTrue(
            !warnFormatted.contains("\n"),
            "CRLF mag niet in warn-log lekken — gevonden: $warnFormatted",
        )
    }

    @Test
    fun `inbound traceparent wordt NIET als parent geadopteerd (root-span)`() {
        stubBaseline()
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }
        // Simuleer een upstream die een geldige W3C traceparent meestuurt.
        // De resource MOET nog steeds een nieuwe root-span starten — anders
        // kan een aanvaller via deze ongeauthentiseerde endpoint requests
        // van verschillende afzenders cross-organisatie aan dezelfde keten
        // koppelen of een eigen trace-id naar Aanmeld/Notificatie laten propageren.
        every { httpHeaders.getHeaderString("traceparent") } returns
            "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
        every { httpHeaders.getHeaderString("traceparent-processor") } returns null

        resource.leverBerichtAan(request)

        // Pin M1-fix: startSpan wordt aangeroepen met `null` parent — niet met
        // een Context die uit de inbound traceparent is geëxtraheerd.
        verify { processingHandler.startSpan("aanleveren-bericht", null) }
    }
}
