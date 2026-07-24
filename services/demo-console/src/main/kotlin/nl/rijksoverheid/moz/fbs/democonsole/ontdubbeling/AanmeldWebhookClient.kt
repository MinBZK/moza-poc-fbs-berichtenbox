package nl.rijksoverheid.moz.fbs.democonsole.ontdubbeling

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * Aanmeld-webhook van de uitvraag. Body is een rauwe JSON-string zodat de twee POST's van de
 * ontdubbeling-demo byte-voor-byte identiek zijn (zelfde CloudEvent-`id`).
 */
@Path("/api/v1/aanmeldingen")
@RegisterRestClient(configKey = "uitvraag")
interface AanmeldWebhookClient {

    @POST
    @Consumes("application/cloudevents+json")
    fun meldAan(event: String): Response
}
