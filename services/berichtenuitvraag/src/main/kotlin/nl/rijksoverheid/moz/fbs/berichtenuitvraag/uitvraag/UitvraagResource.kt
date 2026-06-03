package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Path
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.UitvraagApi
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtenLijst
import java.util.UUID

/**
 * REST-resource voor de Berichten Uitvraag API. Implementeert de gegenereerde
 * [UitvraagApi]-interface en delegeert per endpoint naar de bijbehorende
 * service. SSE-endpoint `_ophalen` valt buiten codegen (tag `Ophalen`) en
 * wordt door [SsePassthroughResource] afgehandeld.
 */
@Path(ApiInfo.BASE_PATH + "/berichten")
@ApplicationScoped
class UitvraagResource(
    private val lijstService: BerichtenlijstService,
    private val ophaalService: BerichtOphaalService,
    private val beheerService: BerichtBeheerService,
    private val logboekContext: LogboekContext,
    @param:Context private val request: ContainerRequestContext,
) : UitvraagApi {

    @Logboek(name = "uitvraag-lijst", processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    override fun getBerichten(
        xOntvanger: String,
        pagina: Int?,
        paginaGrootte: Int?,
    ): BerichtenLijst {
        registreerLdvSubject(xOntvanger)

        return lijstService.lijst(xOntvanger, pagina, paginaGrootte)
    }

    @Logboek(name = "uitvraag-zoeken", processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    override fun zoekenBerichten(xOntvanger: String, q: String): BerichtenLijst {
        registreerLdvSubject(xOntvanger)

        return lijstService.zoek(xOntvanger, q)
    }

    @Logboek(name = "uitvraag-bericht", processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    override fun getBerichtById(berichtId: UUID, xOntvanger: String): Bericht {
        registreerLdvSubject(xOntvanger)

        return ophaalService.haalBericht(xOntvanger, berichtId)
    }

    @Logboek(name = "uitvraag-bijlage", processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    override fun getBijlage(berichtId: UUID, bijlageId: UUID, xOntvanger: String): ByteArray {
        registreerLdvSubject(xOntvanger)

        val (mimeType, bytes) = ophaalService.haalBijlage(xOntvanger, berichtId, bijlageId)
        request.setProperty(BIJLAGE_MIME_TYPE_PROPERTY, mimeType)

        return bytes
    }

    @Logboek(name = "uitvraag-patch", processingActivityId = ProcessingActivities.UITVRAAG_BEHEER)
    override fun updateBerichtMetadata(berichtId: UUID, xOntvanger: String, berichtPatch: BerichtPatch): Bericht {
        registreerLdvSubject(xOntvanger)

        return beheerService.patch(xOntvanger, berichtId, berichtPatch)
    }

    @Logboek(name = "uitvraag-verwijder", processingActivityId = ProcessingActivities.UITVRAAG_BEHEER)
    override fun verwijderBericht(berichtId: UUID, xOntvanger: String) {
        registreerLdvSubject(xOntvanger)

        beheerService.verwijder(xOntvanger, berichtId)
    }

    private fun registreerLdvSubject(xOntvanger: String) =
        registreerLdvSubject(logboekContext, xOntvanger)
}
