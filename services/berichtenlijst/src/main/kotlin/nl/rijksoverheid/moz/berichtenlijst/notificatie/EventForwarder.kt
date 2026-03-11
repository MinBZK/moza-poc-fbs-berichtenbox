package nl.rijksoverheid.moz.berichtenlijst.notificatie

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.berichtenlijst.berichten.Bericht
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Forwards CloudEvents (NL GOV profiel v1.1) naar de Notificatie Service.
 */
@ApplicationScoped
class EventForwarder(
    @ConfigProperty(name = "notificatie.service.url") private val notificatieServiceUrl: String,
) {
    private val log = Logger.getLogger(EventForwarder::class.java)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun forwardBerichtOntvangen(bericht: Bericht) {
        val cloudEvent = buildCloudEvent(bericht)
        val url = "$notificatieServiceUrl/api/v1/notifications"

        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/cloudevents+json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(cloudEvent))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() in 200..299) {
                log.infof("CloudEvent verzonden voor bericht %s (status=%d)", bericht.berichtId, response.statusCode())
            } else {
                log.errorf("CloudEvent verzenden mislukt voor bericht %s (status=%d, body=%s)",
                    bericht.berichtId, response.statusCode(), response.body())
            }
        } catch (e: Exception) {
            log.errorf(e, "CloudEvent verzenden mislukt voor bericht %s naar %s", bericht.berichtId, url)
        }
    }

    private fun buildCloudEvent(bericht: Bericht): String {
        // NL GOV CloudEvents profiel v1.1: structured content mode
        val id = UUID.randomUUID().toString()
        val time = Instant.now().toString()
        return """
            {
              "specversion": "1.0",
              "id": "$id",
              "source": "$SOURCE",
              "type": "$EVENT_TYPE",
              "subject": "${bericht.berichtId}",
              "time": "$time",
              "datacontenttype": "application/json",
              "data": {
                "berichtId": "${bericht.berichtId}",
                "afzender": "${bericht.afzender}",
                "ontvanger": "${bericht.ontvanger}",
                "onderwerp": "${bericht.onderwerp}",
                "magazijnId": "${bericht.magazijnId}"
              }
            }
        """.trimIndent()
    }

    companion object {
        // TODO: OIN vervangen door daadwerkelijk OIN van de organisatie
        const val SOURCE = "urn:nld:oin:00000000000000000000:systeem:berichtenlijst"
        const val EVENT_TYPE = "nl.fbs.bericht-ontvangen"
    }
}
