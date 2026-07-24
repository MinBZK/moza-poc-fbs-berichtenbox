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
 * één bericht — mits de ontvanger een actieve sessie heeft (anders wordt de marker vrijgegeven en
 * verschijnt niets). Id en berichtId worden per aanroep vers gegenereerd zodat opeenvolgende demo's
 * niet tegen elkaars 24u-marker dedupliceren.
 */
@ApplicationScoped
class OntdubbelingService(
    @param:RestClient private val client: AanmeldWebhookClient,
    private val mapper: ObjectMapper,
) {

    fun demonstreer(ontvangerBsn: String): OntdubbelingResultaat {
        val eventId = UUID.randomUUID().toString()
        val payload = mapper.writeValueAsString(bouwEvent(eventId, ontvangerBsn))

        val eerste = post(payload)
        val tweede = post(payload)

        return OntdubbelingResultaat(eventId, eerste, tweede)
    }

    private fun post(payload: String): Int = client.meldAan(payload).use { it.status }

    private fun bouwEvent(eventId: String, ontvangerBsn: String): AanmeldEvent {
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
                ontvanger = Ontvanger(type = "BSN", waarde = ontvangerBsn),
                onderwerp = "Demo: ontdubbeling",
                inhoud = "Ditzelfde CloudEvent wordt tweemaal afgeleverd; er hoort één bericht te ontstaan.",
                tijdstipOntvangst = nu,
                publicatietijdstip = nu,
            ),
        )
    }

    private companion object {

        // RVO — magazijn A; moet een geconfigureerd magazijn zijn, anders weigert de uitvraag met 400.
        const val AFZENDER_OIN = "00000001003214345000"
    }
}
