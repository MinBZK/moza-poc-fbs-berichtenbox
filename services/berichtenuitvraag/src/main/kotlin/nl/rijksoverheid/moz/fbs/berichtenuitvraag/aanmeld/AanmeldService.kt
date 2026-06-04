package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.jboss.logging.Logger

/**
 * Verwerkt een aangemeld gepubliceerd-bericht-event: valideert de CloudEvents-
 * envelope (NL GOV-profiel), bewaakt idempotentie, leidt het bron-magazijn af uit
 * de afzender en schrijft het bericht in de sessiecache van de ontvanger.
 *
 * Alleen ontvangers met een actieve sessie worden bijgewerkt; ontbreekt die, dan
 * is dat het normale geval (de meeste ontvangers hebben geen open sessie) en wordt
 * het event geaccepteerd-maar-overgeslagen i.p.v. als fout teruggemeld — anders zou
 * de publicatie-stream zinloos blijven herafleveren.
 *
 * Foutmeldingen zijn bewust statisch: het event komt van een externe organisatie,
 * dus event-waarden interpoleren in een Problem-detail zou CRLF/JSON-injectie of
 * PII-lek toelaten.
 */
@ApplicationScoped
class AanmeldService(
    private val sessiecache: Sessiecache,
    private val deduplicatie: AanmeldDeduplicatie,
    private val afzenderIndex: AfzenderMagazijnIndex,
    private val logboekContext: LogboekContext,
) {

    private val log = Logger.getLogger(AanmeldService::class.java)

    fun verwerk(event: AangemeldCloudEvent) {
        valideerEnvelope(event)

        val eventId = event.id!!

        if (!deduplicatie.eerstgezien(eventId)) {
            log.debug("Aanmeld-event al eerder verwerkt; overgeslagen (idempotentie)")

            return
        }

        // Houd de idempotentie-marker alléén aan wanneer er ook echt een bericht is
        // geschreven. Geen actieve sessie (de meeste events) en transiente/
        // validatiefouten schrijven niets; de marker dan vrijgeven voorkomt dat Redis
        // volloopt met markers voor no-ops en laat een latere her-aflevering opnieuw
        // verwerken. Best-effort: faalt het vrijgeven, dan ruimt de TTL alsnog op.
        var geschreven = false

        try {
            geschreven = schrijfNieuwBericht(event.data!!)
        } finally {
            if (!geschreven) {
                runCatching { deduplicatie.verwijder(eventId) }
            }
        }
    }

    private fun schrijfNieuwBericht(data: AangemeldBerichtData): Boolean {
        val afzender = parseAfzender(data.afzender!!)
        val magazijnId = afzenderIndex.magazijnVoor(afzender.waarde)
            ?: throw badRequest("Afzender hoort bij geen geconfigureerd magazijn.")

        val ontvanger = parseOntvanger(data.ontvanger!!)

        // AVG art. 30: leg de ontvanger als data-subject vast nu we daadwerkelijk
        // diens berichtgegevens verwerken (niet bij duplicaat/validatiefout).
        logboekContext.dataSubjectType = ontvanger.type.name
        logboekContext.dataSubjectId = ontvanger.waarde

        // De CloudEvent draagt geen bijlage-metadata; die volgen bij de volgende
        // volledige ophaling. Een aanmeld-entry heeft dus aantalBijlagen=0.
        val bericht = Bericht(
            berichtId = data.berichtId!!,
            afzender = afzender.waarde,
            ontvanger = ontvanger.waarde,
            onderwerp = data.onderwerp!!,
            inhoud = data.inhoud!!,
            publicatietijdstip = data.publicatietijdstip!!,
            magazijnId = magazijnId,
            aantalBijlagen = 0,
        )

        try {
            sessiecache.schrijfBericht(ontvanger, bericht)

            return true
        } catch (e: WebApplicationException) {
            if (e.response.status == Response.Status.NOT_FOUND.statusCode) {
                log.debug("Geen actieve sessie voor ontvanger; aanmelding overgeslagen")

                return false
            }

            throw e
        }
    }

    private fun parseAfzender(afzender: String): Oin {
        try {
            return Oin(afzender)
        } catch (e: DomainValidationException) {
            throw badRequest("Afzender is geen geldig OIN.", e)
        }
    }

    private fun parseOntvanger(ontvanger: AangemeldOntvanger): Identificatienummer {
        val type = IdentificatienummerType.entries.firstOrNull { it.name == ontvanger.type }
            ?: throw badRequest("Onbekend identificatienummer-type voor ontvanger.")

        try {
            return Identificatienummer.of(type, ontvanger.waarde!!)
        } catch (e: DomainValidationException) {
            throw badRequest("Ontvanger is geen geldig identificatienummer.", e)
        }
    }

    private fun valideerEnvelope(event: AangemeldCloudEvent) {
        vereis(!event.id.isNullOrBlank(), "CloudEvent mist 'id'.")
        vereis(event.specversion == SPEC_VERSION, "Niet-ondersteunde CloudEvents specversion.")
        vereis(event.source?.startsWith(NLD_NAMESPACE) == true, "CloudEvent 'source' moet de urn:nld: namespace gebruiken.")
        vereis(event.type == EVENT_TYPE, "Onverwacht CloudEvent-type.")

        val data = event.data ?: throw badRequest("CloudEvent mist 'data'.")

        vereis(data.berichtId != null, "data.berichtId ontbreekt.")
        vereis(!data.afzender.isNullOrBlank(), "data.afzender ontbreekt.")
        vereis(data.ontvanger?.waarde?.isNotBlank() == true, "data.ontvanger ontbreekt.")
        vereis(!data.onderwerp.isNullOrBlank(), "data.onderwerp ontbreekt.")
        vereis(!data.inhoud.isNullOrBlank(), "data.inhoud ontbreekt.")
        vereis(data.publicatietijdstip != null, "data.publicatietijdstip ontbreekt.")
    }

    private fun vereis(voorwaarde: Boolean, melding: String) {
        if (!voorwaarde) throw badRequest(melding)
    }

    private fun badRequest(melding: String, oorzaak: Throwable? = null): WebApplicationException =
        WebApplicationException(melding, oorzaak, Response.Status.BAD_REQUEST)

    companion object {
        const val SPEC_VERSION = "1.0"
        const val EVENT_TYPE = "nl.rijksoverheid.fbs.bericht.gepubliceerd"
        private const val NLD_NAMESPACE = "urn:nld:"
    }
}
