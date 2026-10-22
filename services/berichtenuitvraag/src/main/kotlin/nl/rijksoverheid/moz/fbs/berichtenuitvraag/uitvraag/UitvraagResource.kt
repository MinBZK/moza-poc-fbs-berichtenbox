package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.vertx.http.runtime.CurrentVertxRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.StreamingOutput
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.UitvraagApi
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtenLijst
import org.jboss.logging.Logger
import java.io.IOException
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
    private val vertxRequest: CurrentVertxRequest,
    processingHandler: ProcessingHandler,
) : UitvraagApi {

    private val bijlageUitkomst = BijlageUitkomst(processingHandler)

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
     * Geeft de bijlage door terwijl hij uit het magazijn komt. De methode zelf leest alleen het
     * begin; de rest schrijft Quarkus na afloop naar de afnemer, buiten de logregel om. Breekt het
     * magazijn die rest af, dan legt [BijlageUitkomst] de mislukking alsnog onder de logregel vast.
     */
    @Logboek(name = BIJLAGE_ACTIE, processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    override fun getBijlage(berichtId: UUID, bijlageId: UUID, xOntvanger: String, magazijnId: String): StreamingOutput {
        registreerLdvSubject(xOntvanger)

        val bijlage = ophaalService.haalBijlage(xOntvanger, magazijnId, berichtId, bijlageId)
        // Zonder dit blijft de magazijn-response open als de writer nooit draait: bij een
        // HEAD-verzoek, een filter dat gooit of een afnemer die al weg is.
        vertxRequest.current.addEndHandler { bijlage.sluitAlsOngeschreven() }
        request.setProperty(BIJLAGE_MIME_TYPE_PROPERTY, bijlage.mimeType)
        request.setProperty(BIJLAGE_NAAM_PROPERTY, bijlage.bestandsnaam)
        request.setProperty(BIJLAGE_LENGTE_PROPERTY, bijlage.lengte)
        val logregel = BijlageUitkomst.logregelVanDezeActie(logboekContext, BIJLAGE_ACTIE, ProcessingActivities.UITVRAAG_LEZEN)

        val kenmerken = "bijlage, berichtId=$berichtId magazijnId=$magazijnId"

        return StreamingOutput { doel ->
            try {
                bijlage.schrijfNaar(doel)
            } catch (e: BijlageOverdrachtAfgebroken) {
                log.errorf(e, "magazijn-bijlage: overdracht afgebroken nadat het antwoord begonnen was (%s)", kenmerken)
                bijlageUitkomst.legMislukkingVast(logregel, e, kenmerken)

                // Doorgooien: Quarkus reset dan de verbinding, en de afnemer ziet een onvolledige
                // download in plaats van een ingekorte bijlage onder een 200.
                throw e
            } catch (e: IOException) {
                // Een schrijffout: de afnemer is weg, bijvoorbeeld omdat hij de download annuleerde.
                // Geen fout van de verwerking, en doorgooien zou elke geannuleerde download als
                // onverwachte exception op error loggen.
                log.debugf("afnemer brak de bijlage-download af (%s): %s", kenmerken, e.javaClass.name)
            }
        }
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

    private companion object {
        private val log: Logger = Logger.getLogger(UitvraagResource::class.java)

        private const val BIJLAGE_ACTIE = "uitvraag-bijlage"
    }
}
