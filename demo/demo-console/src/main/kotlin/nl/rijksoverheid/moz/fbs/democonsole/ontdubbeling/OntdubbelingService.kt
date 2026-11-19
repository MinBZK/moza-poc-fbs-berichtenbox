package nl.rijksoverheid.moz.fbs.democonsole.ontdubbeling

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.OffsetDateTime
import java.util.UUID

/** Resultaat van de ontdubbeling-demo: het gedeelde event-id en de twee HTTP-statussen (beide 202). */
data class OntdubbelingResultaat(val eventId: String, val eersteStatus: Int, val tweedeStatus: Int)

/**
 * Demonstreert ontdubbeling op de aanmeld-webhook: bouwt één CloudEvent en levert het tweemaal met
 * hetzelfde `id` af. De uitvraag dedupliceert op `id` (keyspace `aanmeld:event:`), dus er ontstaat
 * één bericht — mits de berichtenlijst van de ontvanger al opgehaald is (anders wordt de marker
 * vrijgegeven en verschijnt niets). Id en berichtId worden per aanroep vers gegenereerd zodat
 * opeenvolgende demo's niet tegen elkaars 24u-marker dedupliceren.
 */
@ApplicationScoped
class OntdubbelingService(
    @param:RestClient private val client: AanmeldWebhookClient,
    private val mapper: ObjectMapper,
) {

    fun demonstreer(ontvanger: Ontvanger): OntdubbelingResultaat {
        val eventId = UUID.randomUUID().toString()
        val payload = mapper.writeValueAsString(bouwEvent(eventId, ontvanger))

        val eerste = post(payload)
        val tweede = post(payload)

        return OntdubbelingResultaat(eventId, eerste, tweede)
    }

    private fun post(payload: String): Int = client.meldAan(payload).use { it.status }

    private fun bouwEvent(eventId: String, ontvanger: Ontvanger): AanmeldEvent {
        val nu = OffsetDateTime.now().toString()
        val berichtId = UUID.randomUUID().toString()

        return AanmeldEvent(
            id = eventId,
            source = "urn:nld:oin:$AFZENDER_OIN:systeem:fbs-magazijn",
            specversion = "1.0",
            type = "nl.rijksoverheid.fbs.bericht.gepubliceerd",
            subject = berichtId,
            time = nu,
            datacontenttype = "application/json",
            data = AanmeldData(
                berichtId = berichtId,
                afzender = AFZENDER_OIN,
                ontvanger = ontvanger,
                onderwerp = "Demo: ontdubbeling",
                tijdstipOntvangst = nu,
                publicatietijdstip = nu,
            ),
        )
    }

    private companion object {

        // RVO — magazijn A; moet een geconfigureerd magazijn zijn, anders weigert de uitvraag met 400.
        const val AFZENDER_OIN = "00000000000000100000"
    }
}
