package nl.rijksoverheid.moz.berichtensessiecache.notificatie

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.berichtensessiecache.berichten.Bericht
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
    @param:ConfigProperty(name = "notificatie.service.url") private val notificatieServiceUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(EventForwarder::class.java)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun forwardBerichtOntvangen(bericht: Bericht): Boolean {
        val cloudEvent = buildCloudEvent(bericht)
        val url = "$notificatieServiceUrl/api/v1/notifications"

        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/cloudevents+json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(cloudEvent))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() in 200..299) {
                log.infof("CloudEvent verzonden voor bericht %s (status=%d)", bericht.berichtId, response.statusCode())
                true
            } else {
                log.errorf("CloudEvent verzenden mislukt voor bericht %s (status=%d, body=%s)",
                    bericht.berichtId, response.statusCode(), response.body())
                false
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            log.errorf(e, "CloudEvent verzenden mislukt voor bericht %s naar %s", bericht.berichtId, url)
            false
        }
    }

    private fun buildCloudEvent(bericht: Bericht): String {
        // NL GOV CloudEvents profiel v1.1: structured content mode
        val event = mapOf(
            "specversion" to "1.0",
            "id" to UUID.randomUUID().toString(),
            "source" to SOURCE,
            "type" to EVENT_TYPE,
            "subject" to bericht.berichtId.toString(),
            "time" to Instant.now().toString(),
            "datacontenttype" to "application/json",
            "data" to mapOf(
                "berichtId" to bericht.berichtId.toString(),
                "afzender" to bericht.afzender,
                "ontvanger" to bericht.ontvanger,
                "onderwerp" to bericht.onderwerp,
                "magazijnId" to bericht.magazijnId,
            ),
        )
        return objectMapper.writeValueAsString(event)
    }

    companion object {
        // TODO: OIN vervangen door daadwerkelijk OIN van de organisatie
        const val SOURCE = "urn:nld:oin:00000000000000000000:systeem:berichtensessiecache"
        const val EVENT_TYPE = "nl.fbs.bericht-ontvangen"
    }
}
