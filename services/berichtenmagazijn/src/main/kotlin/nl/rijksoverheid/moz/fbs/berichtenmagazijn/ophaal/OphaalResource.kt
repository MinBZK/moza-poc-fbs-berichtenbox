package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.UriInfo
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.OphaalApi
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtMetInhoud
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtenLijst
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Identificatienummer
import java.util.UUID

/**
 * Resource voor de Ophaal-API. Implementeert de gegenereerde [OphaalApi]
 * interface en mapt domeinobjecten naar de API-modellen via [BerichtDtoMapper].
 *
 * Voor `getBijlage` worden de bytes teruggegeven met `Content-Type:
 * application/octet-stream` — een PoC-keuze, omdat de gegenereerde JAX-RS
 * interface een statisch `@Produces` declareert en het dynamisch mappen op de
 * werkelijke MIME-type van de bijlage extra plumbing vraagt. Consumers vinden
 * de werkelijke MIME-type in `BijlageMetadata.mimeType` bij het bericht.
 */
@Path(ApiInfo.BASE_PATH + "/berichten")
@ApplicationScoped
class OphaalResource(
    private val ophaalService: BerichtOphaalService,
    private val logboekContext: LogboekContext,
    @param:Context private val uriInfo: UriInfo,
) : OphaalApi {

    @Logboek(
        name = "ophalen-berichtenlijst",
        processingActivityId = "https://register.example.com/verwerkingen/berichtenmagazijn-ophalen",
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
        return BerichtDtoMapper.toBerichtenLijst(pagina, ontvanger, afzender, uriInfo.baseUriBuilder)
    }

    @Logboek(
        name = "ophalen-bericht",
        processingActivityId = "https://register.example.com/verwerkingen/berichtenmagazijn-ophalen",
    )
    override fun getBerichtById(berichtId: UUID, xOntvanger: String): BerichtMetInhoud {
        val ontvanger = Identificatienummer.fromHeader(xOntvanger)
        registreerLdvSubject(ontvanger)
        val bericht = ophaalService.haalBerichtOp(berichtId, ontvanger)
        return BerichtDtoMapper.toBerichtMetInhoud(bericht, uriInfo.baseUriBuilder)
    }

    @Logboek(
        name = "ophalen-bijlage",
        processingActivityId = "https://register.example.com/verwerkingen/berichtenmagazijn-ophalen",
    )
    override fun getBijlage(berichtId: UUID, bijlageId: UUID, xOntvanger: String): ByteArray {
        val ontvanger = Identificatienummer.fromHeader(xOntvanger)
        registreerLdvSubject(ontvanger)
        return ophaalService.haalBijlageOp(berichtId, bijlageId, ontvanger).content
    }

    private fun registreerLdvSubject(ontvanger: Identificatienummer) {
        // Zet dataSubjectId nadat fromHeader is geslaagd — bij een 400 (ongeldige
        // header) blijft de safe default uit LogboekContextDefaultFilter staan.
        logboekContext.dataSubjectId = ontvanger.waarde
        logboekContext.dataSubjectType = "ontvanger"
    }

    private companion object {
        // Spiegelt de OpenAPI-default; alleen relevant als de generator de Integer-parameter
        // alsnog `null` doorgeeft (bv. een client die de query-param weglaat).
        const val DEFAULT_PAGE_SIZE = 20
    }
}
