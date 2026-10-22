package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.opentelemetry.api.trace.Span
import io.quarkus.vertx.http.runtime.CurrentVertxRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.StreamingOutput
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
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
     * Geeft de bijlage door terwijl hij uit het magazijn komt. De methode zelf leest alleen het
     * begin; de rest schrijft Quarkus na afloop naar de afnemer, buiten de logregel om.
     *
     * Breekt het magazijn die rest af, dan blijft de logregel staan zoals hij is, zonder
     * ERROR-child: de afnemer kreeg al een deel van de bijlage, en het logboek registreert een
     * verstrekking liever te veel dan te weinig. Een ERROR-child komt er alleen als vaststaat dat
     * er niets verstrekt is, en dat geval eindigt hier binnen de methode, als 502 met een logregel
     * op `ERROR`. De applicatielog draagt de `trace_id:span_id` van de logregel, zodat de twee
     * te koppelen zijn.
     */
    @Logboek(name = "uitvraag-bijlage", processingActivityId = ProcessingActivities.UITVRAAG_LEZEN)
    override fun getBijlage(berichtId: UUID, bijlageId: UUID, xOntvanger: String, magazijnId: String): StreamingOutput {
        registreerLdvSubject(xOntvanger)

        val bijlage = ophaalService.haalBijlage(xOntvanger, magazijnId, berichtId, bijlageId)
        val verzoek = vertxRequest.current
        verzoek.addEndHandler { bijlage.sluitAlsOngeschreven() }

        // Een HEAD krijgt geen body, dus de writer draait niet; en een afnemer die al weg was, zet
        // de end-handler niet meer in gang.
        if (request.method == "HEAD" || verzoek.response().closed()) bijlage.sluitAlsOngeschreven()

        request.setProperty(BIJLAGE_MIME_TYPE_PROPERTY, bijlage.mimeType)
        request.setProperty(BIJLAGE_NAAM_PROPERTY, bijlage.bestandsnaam)
        request.setProperty(BIJLAGE_LENGTE_PROPERTY, bijlage.lengte)
        // De logregel van deze methode: de interceptor houdt zijn span actief zolang ze loopt.
        val logregel = Span.current().spanContext
        val kenmerken = "bijlage, berichtId=$berichtId magazijnId=$magazijnId"

        return StreamingOutput { doel ->
            try {
                bijlage.schrijfNaar(doel)
            } catch (e: BijlageOverdrachtAfgebroken) {
                log.errorf(
                    e,
                    "magazijn-bijlage: overdracht afgebroken nadat het antwoord begonnen was (%s) [%s:%s]",
                    kenmerken,
                    logregel.traceId,
                    logregel.spanId,
                )

                // Doorgooien: Quarkus reset dan de connection, en de afnemer ziet een onvolledige
                // download in plaats van een ingekorte bijlage die netjes eindigt.
                throw e
            } catch (e: IOException) {
                // Een schrijffout. Meestal is de afnemer weg, bijvoorbeeld omdat hij de download
                // annuleerde: geen fout van de verwerking, dus niet op error. Staat de connection nog
                // open (een interrupt tijdens het wachten op de afnemer), dan wel een waarschuwing.
                if (verzoek.response().closed()) {
                    log.debugf("afnemer brak de bijlage-download af (%s)", kenmerken)
                } else {
                    log.warnf(e, "magazijn-bijlage: schrijven naar de afnemer mislukt op een open connection (%s)", kenmerken)
                }

                // Doorgooien, ook hier: normaal terugkeren zou een chunked antwoord netjes afsluiten.
                throw BijlageNietAfgeleverd(e)
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
    }
}
