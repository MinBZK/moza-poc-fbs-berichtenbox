package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.Path
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.UriInfo
import org.jboss.logging.Logger
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ProcessingActivities
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.OphaalApi
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtenLijst
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import java.util.UUID

/**
 * Resource voor de Ophaal-API. Implementeert de gegenereerde [OphaalApi]
 * interface en mapt domeinobjecten naar de API-modellen via [BerichtDtoMapper].
 *
 * Voor `getBijlage` wordt het werkelijke MIME-type van de bijlage in de
 * `Content-Type` response-header gezet via [BijlageContentTypeFilter]; de
 * resource zet het MIME-type op een request-attribute zodat het filter het
 * vlak voor het schrijven van de body kan toepassen.
 */
@Path(ApiInfo.BASE_PATH + "/berichten")
@ApplicationScoped
class OphaalResource(
    private val ophaalService: BerichtOphaalService,
    private val logboekContext: LogboekContext,
    @param:Context private val uriInfo: UriInfo,
    @param:Context private val request: ContainerRequestContext,
) : OphaalApi {

    @Logboek(
        name = "ophalen-berichtenlijst",
        processingActivityId = ProcessingActivities.MAGAZIJN_OPHALEN,
    )
    override fun getBerichten(
        xOntvanger: String,
        afzender: String?,
        page: Int?,
        pageSize: Int?,
    ): BerichtenLijst {
        val ontvanger = Identificatienummer.fromHeader(xOntvanger)
        registreerLdvSubject(ontvanger)
        val pagina = ophaalService.lijst(
            ontvanger = ontvanger,
            afzender = afzender,
            page = page ?: 0,
            pageSize = pageSize ?: DEFAULT_PAGE_SIZE,
        )
        return BerichtDtoMapper.toBerichtenLijst(pagina, afzender, uriInfo.baseUriBuilder)
    }

    @Logboek(
        name = "ophalen-bericht",
        processingActivityId = ProcessingActivities.MAGAZIJN_OPHALEN,
    )
    override fun getBerichtById(berichtId: UUID, xOntvanger: String): Bericht {
        val ontvanger = Identificatienummer.fromHeader(xOntvanger)
        registreerLdvSubject(ontvanger)
        val bericht = ophaalService.haalBerichtOp(berichtId, ontvanger)
        return BerichtDtoMapper.toBericht(bericht, uriInfo.baseUriBuilder)
    }

    @Logboek(
        name = "ophalen-bijlage",
        processingActivityId = ProcessingActivities.MAGAZIJN_OPHALEN,
    )
    override fun getBijlage(berichtId: UUID, bijlageId: UUID, xOntvanger: String): ByteArray {
        val ontvanger = Identificatienummer.fromHeader(xOntvanger)
        registreerLdvSubject(ontvanger)
        val bijlage = ophaalService.haalBijlageOp(berichtId, bijlageId, ontvanger)
        // Een ongeldig MIME-type in de DB duidt op een aanlever-bug of data-corruptie:
        // serveer in dat geval geen bytes onder een verkeerd Content-Type — werp 500
        // zodat het uitvalt en zichtbaar wordt. De waarde komt niet in de response
        // (alleen in de log) om geen interne details aan de client te lekken;
        // mimeType is geen PII en mag in de applicatielog staan voor diagnose.
        val mediaType = runCatching { MediaType.valueOf(bijlage.mimeType) }
            .onFailure { ex ->
                log.warnf(
                    ex,
                    "Ongeldig MIME-type in bijlage; serveer geen content. berichtId=%s bijlageId=%s mimeType=%s",
                    berichtId,
                    bijlageId,
                    bijlage.mimeType,
                )
            }
            .getOrNull()
        if (mediaType == null) {
            throw InternalServerErrorException("Ongeldig MIME-type in bijlage")
        }
        request.setProperty(BIJLAGE_MIME_TYPE_PROPERTY, mediaType.toString())
        return bijlage.content
    }

    private fun registreerLdvSubject(ontvanger: Identificatienummer) {
        // Zet dataSubjectId nadat fromHeader is geslaagd — bij een 400 (ongeldige
        // header) blijft de safe default uit LogboekContextDefaultFilter staan.
        // dataSubjectType is het identifier-type (BSN/RSIN/KVK/OIN) per Logboek-
        // spec, niet de rol — tooling die op type filtert vindt anders niets.
        logboekContext.dataSubjectId = ontvanger.waarde
        logboekContext.dataSubjectType = ontvanger.type.name
    }

    private companion object {
        private val log: Logger = Logger.getLogger(OphaalResource::class.java)

        // Default voor `pageSize` als de query-param ontbreekt. De gegenereerde
        // interface levert `pageSize` als `Int?`; Quarkus REST dwingt de
        // OpenAPI-default niet af op de Kotlin-parameter. Waarde moet gelijk
        // blijven aan `PageSizeParam.default` in `berichtenmagazijn-api.yaml`.
        const val DEFAULT_PAGE_SIZE = 20
    }
}
