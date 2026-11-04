package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
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
 * wordt door [OphalenSseResource] afgehandeld.
 *
 * Bewust géén eigen `@Path`: de paden komen uit [UitvraagApi], de `/api/v1`-prefix
 * uit `quarkus.rest.path`. Een class-`@Path` hier zou botsen met de pad-verdeling
 * die de generator zelf over class- en methode-niveau maakt.
 */
@ApplicationScoped
class UitvraagResource(
    private val lijstService: BerichtenlijstService,
    private val ophaalService: BerichtOphaalService,
    private val beheerService: BerichtBeheerService,
    private val logboekContext: LogboekContext,
    @param:Context private val request: ContainerRequestContext,
    private val doorgifte: BijlageDoorgifte,
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
    override fun getBerichtById(berichtId: UUID, xOntvanger: String, magazijnId: String): Bericht {
        registreerLdvSubject(xOntvanger)

        return ophaalService.haalBericht(xOntvanger, magazijnId, berichtId)
    }

    /**
     * Geeft de bijlage door ([BijlageDoorgifte]). Is ze kleiner dan 1 MiB, dan gaat ze als `byte[]`
     * de deur uit. Vanaf 1 MiB streamt ze: de methode leest alleen het begin, en de rest schrijft
     * Quarkus na afloop naar de afnemer, buiten de logregel om.
     *
     * Breekt het magazijn die rest af, dan blijft de logregel op `UNSET`, zonder ERROR-child: de
     * afnemer kreeg al een deel van de bijlage, en het logboek registreert een verstrekking liever te
     * veel dan te weinig. Breekt het magazijn al af tijdens het begin, dan staat vast dat er niets
     * verstrekt is; dat eindigt binnen de methode als 502, met de logregel zelf op `ERROR`. De
     * errorregel bij een afgebroken overdracht draagt de `trace_id:span_id` van de logregel, zodat de
     * twee te koppelen zijn.
     */
    @Logboek(name = "uitvraag-bijlage", processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    override fun getBijlage(berichtId: UUID, bijlageId: UUID, xOntvanger: String, magazijnId: String): Response {
        registreerLdvSubject(xOntvanger)

        val bijlage = ophaalService.haalBijlage(xOntvanger, magazijnId, berichtId, bijlageId)
        request.setProperty(BIJLAGE_MIME_TYPE_PROPERTY, bijlage.mimeType)
        request.setProperty(BIJLAGE_NAAM_PROPERTY, bijlage.bestandsnaam)
        request.setProperty(BIJLAGE_LENGTE_PROPERTY, bijlage.lengte)

        return doorgifte.antwoord(bijlage, xOntvanger, "bijlage, berichtId=$berichtId magazijnId=$magazijnId")
    }

    @Logboek(name = "uitvraag-patch", processingActivityId = ProcessingActivities.UITVRAAG_BEHEER)
    override fun updateBerichtMetadata(berichtId: UUID, xOntvanger: String, magazijnId: String, berichtPatch: BerichtPatch): Bericht {
        registreerLdvSubject(xOntvanger)

        return beheerService.patch(xOntvanger, berichtId, magazijnId, berichtPatch)
    }

    @Logboek(name = "uitvraag-verwijder", processingActivityId = ProcessingActivities.UITVRAAG_BEHEER)
    override fun verwijderBericht(berichtId: UUID, xOntvanger: String, magazijnId: String) {
        registreerLdvSubject(xOntvanger)

        beheerService.verwijder(xOntvanger, berichtId, magazijnId)
    }

    private fun registreerLdvSubject(xOntvanger: String) =
        registreerLdvSubject(logboekContext, xOntvanger)
}
