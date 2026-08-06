package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context as OtelContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.UriInfo
import nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.AanleverApi
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtAanleverenRequest
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtLinks
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtResponse
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Identificatienummer as IdentificatienummerDto
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Link
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieConfig
import nl.rijksoverheid.moz.fbs.common.FoutBeschrijving

/**
 * REST-resource voor de Aanlever API.
 *
 * **Geen `@Logboek`-annotatie**: die interceptor zet `processingActivityId` op een
 * hardcoded annotation-value, wat config-driven URI's onmogelijk maakt. Daarom zelf
 * span-management (zoals [nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieClaimVerwerker]),
 * met de processingActivityId-bron op één plek (config) — en daarmee ook zelf de
 * schrijffout-recorder legen en de acknowledgement afdwingen, werk dat de interceptor
 * normaliter voor zijn rekening neemt.
 *
 * **Inbound W3C `traceparent` wordt als parent geadopteerd** ([OtelContext.current]):
 * de keten loopt door zodat een aanlever-request cross-organisatie traceerbaar
 * blijft (Logboek Dataverwerkingen). Authenticatie en TLS-terminatie zitten aan de
 * clusterrand (mTLS PKIoverheid / OAuth, edge-gateway), dus de upstream is vertrouwd
 * en de inzage-entry voor LDV ligt daar — niet bij dit endpoint.
 */
@Path(ApiInfo.BASE_PATH + "/berichten")
@ApplicationScoped
class AanleverResource(
    private val opslagService: BerichtOpslagService,
    private val logboekContext: LogboekContext,
    private val processingHandler: ProcessingHandler,
    private val publicatieConfig: PublicatieConfig,
    @param:Context private val uriInfo: UriInfo,
    @param:Context private val httpHeaders: HttpHeaders,
) : AanleverApi {

    override fun leverBerichtAan(berichtAanleverenRequest: BerichtAanleverenRequest): BerichtResponse {
        // De recorder is thread-gebonden en deze resource doet zijn eigen span-beheer:
        // zonder legen kan een schrijffout van een eerder request op deze pooled thread
        // dit request laten falen.
        LogboekWriteFailureRecorder.clear()

        // Span en LDV-context binnen try zodat een latere config-throw geen
        // span-leak veroorzaakt; finally end()'t altijd.
        var pendingFailure: Throwable? = null
        val span = processingHandler.startSpan("aanleveren-bericht", OtelContext.current())
        try {
            // processingActivityId vóór de eerste mogelijke fout zetten zodat
            // addLogboekContextToSpan in finally niet faalt. dataSubjectId/-Type krijgen
            // hieronder de gevalideerde ontvanger (tot dan: safe defaults via filter).
            logboekContext.processingActivityId = publicatieConfig.verwerkingsregisterAanleveren()
            return span.makeCurrent().use { _ ->
                val ontvangerDto = berichtAanleverenRequest.ontvanger
                val bijlagen = berichtAanleverenRequest.bijlagen.orEmpty().map { dto ->
                    BijlageInvoer(naam = dto.naam, mimeType = dto.mimeType, content = dto.inhoud)
                }
                val bericht = opslagService.slaBerichtOp(
                    afzender = berichtAanleverenRequest.afzender,
                    ontvangerType = IdentificatienummerType.valueOf(ontvangerDto.type.name),
                    ontvangerWaarde = ontvangerDto.waarde,
                    onderwerp = berichtAanleverenRequest.onderwerp,
                    inhoud = berichtAanleverenRequest.inhoud,
                    publicatietijdstip = berichtAanleverenRequest.publicatietijdstip,
                    bijlagen = bijlagen,
                )

                // dataSubject pas na domein-validatie zetten (geen ongevalideerde input
                // in de AVG-context). type.name (BSN/KVK/RSIN) i.p.v. de rol "ontvanger"
                // zodat aanleveren- en publiceren-records op dezelfde taxonomie correleren.
                logboekContext.dataSubjectId = bericht.ontvanger.waarde
                logboekContext.dataSubjectType = bericht.ontvanger.type.name

                naarBerichtResponse(bericht)
            }
        } catch (ex: Exception) {
            pendingFailure = ex
            span.setStatus(StatusCode.ERROR)
            throw ex
        } finally {
            koppelLdvContextEnEindigSpan(span, pendingFailure)
        }
    }

    private fun naarBerichtResponse(bericht: Bericht): BerichtResponse {
        val selfHref = uriInfo.baseUriBuilder
            .path(ApiInfo.BASE_PATH)
            .path("berichten")
            .path(bericht.berichtId.toString())
            .build().toString()

        return BerichtResponse().apply {
            berichtId = bericht.berichtId
            afzender = bericht.afzender.waarde
            ontvanger = IdentificatienummerDto().apply {
                type = IdentificatienummerDto.TypeEnum.valueOf(bericht.ontvanger.type.name)
                waarde = bericht.ontvanger.waarde
            }
            onderwerp = bericht.onderwerp
            tijdstipOntvangst = bericht.tijdstipOntvangst
            publicatietijdstip = bericht.publicatietijdstip
            links = BerichtLinks().apply {
                self = Link().apply { href = selfHref }
            }
        }
    }

    private fun koppelLdvContextEnEindigSpan(span: Span, pendingFailure: Throwable?) {
        // foreign_operation.processor-attribuut equivalent aan LogboekInterceptor
        // — alleen koppelen als upstream een traceparent stuurde.
        val traceparent = httpHeaders.getHeaderString("traceparent")

        if (traceparent != null) {
            val processor = httpHeaders.getHeaderString("traceparent-processor")
            span.setAttribute(
                "dpl.core.foreign_operation.processor",
                FoutBeschrijving.saneer(processor),
            )
        }

        try {
            processingHandler.addLogboekContextToSpan(span, logboekContext, pendingFailure)
        } finally {
            span.end()
        }

        // Fail-closed: een aanlevering die niet in het logboek kwam, telt niet als
        // uitgevoerd. Propageert er al een functionele fout, dan mag een schrijffout die
        // niet maskeren — die fout moet de aanleverende partij bereiken.
        processingHandler.enforceWriteAcknowledgement(throwOnFailure = pendingFailure == null)
    }
}
