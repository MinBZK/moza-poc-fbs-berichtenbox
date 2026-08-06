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
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekWriteException
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtAanleverenRequest
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Identificatienummer as IdentificatienummerDto
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.common.identificatie.Bsn
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Borgt twee eigenschappen van [AanleverResource]:
 *  1. **Fail-closed acknowledgement**: de resource dwingt af dat de LDV-schrijfactie
 *     is gelukt (`enforceWriteAcknowledgement`) vóórdat een aanlevering als geslaagd
 *     geldt — een verwerking die niet in het logboek kwam, telt niet als uitgevoerd.
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

    private fun geldigeRequest() = BerichtAanleverenRequest().apply {
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
    fun `LDV-schrijffout laat het aanleveren falen in plaats van 201 te geven`() {
        // Fail-closed: een verwerking die niet in het logboek kwam, mag niet als
        // geslaagd worden gerapporteerd (LDV-acknowledgement-eis).
        stubBaseline()
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
        every {
            processingHandler.enforceWriteAcknowledgement(true)
        } throws LogboekWriteException("Logregel kon niet in het Logboek worden opgeslagen")

        assertThrows<LogboekWriteException> { resource.leverBerichtAan(geldigeRequest()) }

        verify { span.end() }
    }

    @Test
    fun `op het foutpad mag de acknowledgement de domeinfout niet maskeren`() {
        // Er propageert al een functionele fout; die moet de gebruiker bereiken, niet
        // een LDV-fout die er overheen komt.
        stubBaseline()
        every { opslagService.slaBerichtOp(any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("opslag stuk")
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
        justRun { processingHandler.enforceWriteAcknowledgement(false) }

        val ex = assertThrows<IllegalStateException> { resource.leverBerichtAan(geldigeRequest()) }

        assertEquals("opslag stuk", ex.message)
        verify { processingHandler.enforceWriteAcknowledgement(false) }
        verify { span.end() }
    }

    @Test
    fun `de propagerende fout gaat mee naar de LDV-context`() {
        // Zonder dit overschrijft een optimistische OK uit de context de ERROR-status
        // en missen per-betrokkene child-logregels hun exception-attributen.
        stubBaseline()
        val domeinfout = IllegalStateException("opslag stuk")
        every { opslagService.slaBerichtOp(any(), any(), any(), any(), any(), any(), any()) } throws domeinfout
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
        justRun { processingHandler.enforceWriteAcknowledgement(any()) }

        assertThrows<IllegalStateException> { resource.leverBerichtAan(geldigeRequest()) }

        verify { processingHandler.addLogboekContextToSpan(span, any<LogboekContext>(), domeinfout) }
    }

    @Test
    fun `dataSubjectType krijgt concrete type BSN in plaats van relationele rol`() {
        stubBaseline()
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
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
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
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
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
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
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
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
        justRun { processingHandler.addLogboekContextToSpan(any(), any<LogboekContext>(), any()) }
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
}
