package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import io.smallrye.common.annotation.Blocking
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ProcessingActivities

/**
 * CloudEvents-webhook `POST /api/v1/aanmeldingen` waarop de Publicatie Stream van
 * een magazijn een gepubliceerd bericht aanmeldt. Buiten codegen gehouden (zoals het
 * SSE-endpoint): de custom media type `application/cloudevents+json` en de
 * 202-respons zonder body laten zich niet schoon uit `jaxrs-spec` genereren. Het
 * pad staat wél in `berichtenuitvraag-api.yaml` als contractbron.
 *
 * 202 dekt zowel "cache bijgewerkt" als bewust-overgeslagen (geen actieve sessie /
 * duplicaat): voor de publicatie-stream is het event in alle drie de gevallen
 * succesvol afgeleverd. Fouten (400/503) lopen via de fbs-common ExceptionMappers.
 *
 * Authenticatie van de aanleverende partij (de Publicatie Stream) loopt op de
 * transport-laag via FSC (mTLS + service-contract), gelijk aan de overige
 * endpoints die op de gateway/FSC-grens vertrouwen; dit endpoint kent daarom geen
 * eigen app-niveau-token. Het verwerkt alleen het verwachte event-type en raakt
 * uitsluitend de cache van de in het event genoemde ontvanger.
 */
@Path(ApiInfo.BASE_PATH + "/aanmeldingen")
@ApplicationScoped
class AanmeldResource(
    private val service: AanmeldService,
) {

    @POST
    @Blocking
    @Consumes(CLOUDEVENTS_JSON)
    @Logboek(name = "uitvraag-aanmelding", processingActivityId = ProcessingActivities.UITVRAAG_AANMELDING)
    fun meldBerichtAan(event: AangemeldCloudEvent): Response {
        service.verwerk(event)

        return Response.status(Response.Status.ACCEPTED).build()
    }

    companion object {
        private const val CLOUDEVENTS_JSON = "application/cloudevents+json"
    }
}
