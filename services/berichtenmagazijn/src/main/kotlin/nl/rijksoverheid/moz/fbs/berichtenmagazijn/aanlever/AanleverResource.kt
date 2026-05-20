package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context as OtelContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.UriInfo
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.AanleverApi
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtAanleverenRequest
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtLinks
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtResponse
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Identificatienummer as IdentificatienummerDto
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Link
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieConfig
import nl.rijksoverheid.moz.fbs.common.FoutBeschrijving
import org.jboss.logging.Logger

/**
 * REST-resource voor de Aanlever API.
 *
 * **Geen `@Logboek`-annotatie**: de bundeled CDI interceptor zet
 * `logboekContext.processingActivityId` op de hardcoded annotation-value vóór
 * `addLogboekContextToSpan`, wat config-driven URI's onmogelijk maakt. We
 * doen daarom zelf span-management; dit spiegelt de aanpak in
 * [nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie.PublicatieClaimVerwerker]
 * en houdt de processingActivityId-bron op één plek (config).
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

    private val log = Logger.getLogger(AanleverResource::class.java)

    override fun leverBerichtAan(berichtAanleverenRequest: BerichtAanleverenRequest): BerichtResponse {
        // Span en LDV-context binnen try zodat een latere config-throw geen
        // span-leak veroorzaakt; finally end()'t altijd.
        var pendingFailure: Throwable? = null
        val span = processingHandler.startSpan("aanleveren-bericht", OtelContext.current())
        try {
            // processingActivityId vóór de eerste mogelijke fout zetten zodat
            // addLogboekContextToSpan in finally niet faalt op `require(!isNullOrEmpty)`.
            // dataSubjectId/-Type worden door LogboekContextDefaultFilter al op
            // safe defaults gezet; we vervangen ze hieronder door de gevalideerde
            // ontvanger-waarde + concrete type (BSN/RSIN/KVK), niet de relationele rol.
            logboekContext.processingActivityId = publicatieConfig.verwerkingsregisterAanleveren()
            return span.makeCurrent().use { _ ->
                val ontvangerDto = berichtAanleverenRequest.ontvanger
                val bericht = opslagService.slaBerichtOp(
                    afzender = berichtAanleverenRequest.afzender,
                    ontvangerType = IdentificatienummerType.valueOf(ontvangerDto.type.name),
                    ontvangerWaarde = ontvangerDto.waarde,
                    onderwerp = berichtAanleverenRequest.onderwerp,
                    inhoud = berichtAanleverenRequest.inhoud,
                    publicatiedatum = berichtAanleverenRequest.publicatiedatum,
                )

                // Zet dataSubjectId pas na succesvolle domein-validatie, zodat we geen
                // ongevalideerde input in de AVG-logboekcontext zetten. Tot dat punt
                // zorgt LogboekContextDefaultFilter voor een safe default.
                logboekContext.dataSubjectId = bericht.ontvanger.waarde
                // type.name (BSN/KVK/RSIN) i.p.v. relationele rol "ontvanger" zodat
                // LDV-records over aanleveren én publiceren correleerbaar zijn op
                // dezelfde subject-taxonomie (PublicatieClaimVerwerker doet hetzelfde).
                logboekContext.dataSubjectType = bericht.ontvanger.type.name

                val selfHref = uriInfo.baseUriBuilder
                    .path(ApiInfo.BASE_PATH)
                    .path("berichten")
                    .path(bericht.berichtId.toString())
                    .build()

                BerichtResponse().apply {
                    berichtId = bericht.berichtId
                    afzender = bericht.afzender.waarde
                    ontvanger = IdentificatienummerDto().apply {
                        type = IdentificatienummerDto.TypeEnum.valueOf(bericht.ontvanger.type.name)
                        waarde = bericht.ontvanger.waarde
                    }
                    onderwerp = bericht.onderwerp
                    tijdstipOntvangst = bericht.tijdstipOntvangst
                    publicatiedatum = bericht.publicatiedatum
                    links = BerichtLinks().apply {
                        self = Link().apply { href = selfHref }
                    }
                }
            }
        } catch (ex: Exception) {
            pendingFailure = ex
            span.setStatus(StatusCode.ERROR)
            throw ex
        } finally {
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
            // Genest try/finally rond `addLogboekContextToSpan`: een fout in deze
            // finally-tak (niet de oorspronkelijke business-exception) mag de
            // span niet lekken naar OTel-exporter. Outer finally borgt `span.end()`,
            // inner try vangt enkel IAE (= config/state-fout van ProcessingHandler).
            // Andere RuntimeExceptions (NPE, ISE, CME) wijzen op programmeerfouten en
            // mogen niet stilletjes verdwijnen — die vliegen door naar de exception-mapper.
            try {
                try {
                    processingHandler.addLogboekContextToSpan(span, logboekContext)
                } catch (ex: IllegalArgumentException) {
                    // Consistent met ExceptionMapper-discipline: laat pendingFailure-
                    // message weg uit log (FoutBeschrijving.saneer dekt geen niet-
                    // numerieke PII). Categorie + cause-type zijn voldoende correlatie-
                    // handvatten; de oorspronkelijke exception-stack komt door naar
                    // de mapper via de `throw ex` in de outer `catch (ex: Exception)`
                    // hierboven (line-ref vermeden — overleeft refactors).
                    //
                    // errorId hier voegt een tweede correlatie-handvat toe: ProblemException
                    // Mapper genereert zijn eigen errorId voor de oorspronkelijke ex.
                    // Beide id's in dezelfde request-trace laten support twee log-regels
                    // koppelen ("LDV-koppelen-faal" + "Server error 500") wanneer de
                    // pipeline message-filter throwables strip't.
                    val ldvErrorId = java.util.UUID.randomUUID()
                    log.errorf(
                        ex,
                        "LDV-context koppelen aan span faalde voor aanleveren-bericht " +
                            "(ldvErrorId=%s); oorspronkelijke fout-categorie=%s cause=%s",
                        ldvErrorId,
                        pendingFailure?.javaClass?.simpleName ?: "geen",
                        pendingFailure?.cause?.javaClass?.simpleName ?: "geen",
                    )
                }
            } finally {
                span.end()
            }
        }
    }
}
