package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.UriBuilder
import jakarta.ws.rs.core.UriInfo
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtAanleverenRequest
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Identificatienummer as IdentificatienummerDto
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.UUID

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
            opslagService.slaBerichtOp(
                afzender = any(),
                ontvangerType = any(),
                ontvangerWaarde = any(),
                onderwerp = any(),
                inhoud = any(),
                publicatietijdstip = any(),
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
    fun `traceparent-processor met CRLF wordt gesaneerd (geen log-injection)`() {
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

        // FoutBeschrijving.saneer strip't control-chars → geen CRLF in het attribuut.
        assertTrue(
            !attribuutWaarde.captured.contains("\n") && !attribuutWaarde.captured.contains("\r"),
            "CRLF mag niet in span-attribuut — gevonden: ${attribuutWaarde.captured}",
        )
    }

    @Test
    fun `traceparent-processor met PII-cijferreeks wordt geredact`() {
        stubBaseline()
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }
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
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }
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
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>()) }
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
}
