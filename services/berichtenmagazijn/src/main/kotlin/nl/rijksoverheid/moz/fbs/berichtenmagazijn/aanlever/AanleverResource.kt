package nl.rijksoverheid.moz.fbs.berichtenmagazijn.aanlever

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context as OtelContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.UriInfo
import nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekWriteException
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
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
import nl.rijksoverheid.moz.fbs.common.LdvFoutSamenvatting
import org.jboss.logging.Logger

/**
 * REST-resource voor de Aanlever API.
 *
 * **Geen eigen `@Path`**: de paden komen uit de gegenereerde [AanleverApi], de
 * `/api/v1`-prefix uit `quarkus.rest.path`. Een class-`@Path` hier zou botsen met
 * de pad-verdeling die de generator zelf over class- en methode-niveau maakt.
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
        val bijlagen = berichtAanleverenRequest.bijlagen.orEmpty().map { dto ->
            BijlageInvoer(naam = dto.naam, mimeType = dto.mimeType, content = dto.inhoud)
        }
        val bericht = valideerEnLegVast(berichtAanleverenRequest, bijlagen)

        // Pas opslaan nadat de logregel bevestigd is. Andersom zou een aanlevering die
        // niet in het logboek kwam tóch een bericht én outbox-leveringen achterlaten: de
        // aanleveraar krijgt dan een 500 en levert opnieuw aan, met een nieuw berichtId
        // en dus een nieuwe CloudEvent-id waarop downstream-dedup niet aanslaat.
        opslagService.slaBerichtOp(bericht, bijlagen)

        return naarBerichtResponse(bericht)
    }

    /**
     * Valideert de aanlevering en legt de voorgenomen verwerking vast in het logboek.
     * Keert pas terug als de logregel bevestigd is; een [LogboekWriteException] betekent
     * dat er niets opgeslagen wordt.
     *
     * De logregel beschrijft daarmee het voornemen, niet de uitkomst: een opslagfout ná
     * dit punt laat een logregel achter voor een aanlevering die niet plaatsvond.
     * Over-rapporteren is hier het veiligere uiterste — TODO(#924) voor het vastleggen
     * van de uitkomst.
     */
    private fun valideerEnLegVast(
        berichtAanleverenRequest: BerichtAanleverenRequest,
        bijlagen: List<BijlageInvoer>,
    ): Bericht {
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
                val bericht = opslagService.valideerAanlevering(
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

                bericht
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
        try {
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
                // Alleen het type van de fout gaat mee: de wrapper zet exception.message op
                // dezelfde child-spans die dpl.core.data_subject_id dragen, en die rijen
                // gaan bij een inzageverzoek naar buiten.
                processingHandler.addLogboekContextToSpan(
                    span,
                    logboekContext,
                    pendingFailure?.let(LdvFoutSamenvatting::van),
                )
            } finally {
                span.end()
            }

            // Fail-closed: een aanlevering die niet in het logboek kwam, telt niet als
            // uitgevoerd — deze methode draait vóór de opslag, dus een schrijffout houdt
            // het bericht uit de database. Propageert er al een functionele fout, dan mag
            // een schrijffout die niet maskeren: die fout moet de aanleveraar bereiken.
            processingHandler.enforceWriteAcknowledgement(throwOnFailure = pendingFailure == null)
        } catch (ex: Exception) {
            // Deze methode draait vanuit een finally-blok. Gooien terwijl er al een fout
            // propageert zou die vervángen, waardoor de aanleveraar de domeinfout niet
            // meer ziet; de LDV-fout gaat dan mee als suppressed.
            if (pendingFailure == null) throw ex

            pendingFailure.addSuppressed(ex)
            log.errorf(
                ex,
                "LDV-logregel voor aanleveren mislukt terwijl er al een fout propageert (categorie=%s)",
                ex.javaClass.simpleName,
            )
        }
    }
}
