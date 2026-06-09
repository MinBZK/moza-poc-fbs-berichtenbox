package nl.rijksoverheid.moz.fbs.berichtenuitvraag.aanmeld

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.SessiecacheException
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag.naApiFout
import nl.rijksoverheid.moz.fbs.common.exception.DomainValidationException
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.common.identificatie.IdentificatienummerType
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import org.jboss.logging.Logger

/**
 * Verwerkt een aangemeld gepubliceerd-bericht-event: parseert de CloudEvents-
 * envelope (NL GOV-profiel) naar een getypeerd [GepubliceerdBerichtEvent], bewaakt
 * idempotentie, leidt het bron-magazijn af uit de afzender en schrijft het bericht
 * in de sessiecache van de ontvanger.
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
        val gepubliceerd = parse(event)

        if (!deduplicatie.eerstgezien(gepubliceerd.eventId)) {
            log.debug("Aanmeld-event al eerder verwerkt; overgeslagen (idempotentie)")

            return
        }

        // Houd de idempotentie-marker alléén aan wanneer er ook echt een bericht is
        // geschreven. Geen actieve sessie (de meeste events) en transiente fouten
        // schrijven niets; de marker dan vrijgeven voorkomt dat Redis volloopt met
        // markers voor no-ops en laat een latere her-aflevering opnieuw verwerken.
        // Best-effort: faalt het vrijgeven, log het (een structureel falende DEL zou
        // anders stil de keyspace laten groeien en her-afleveringen blokkeren); de
        // TTL ruimt de marker dan alsnog op.
        var geschreven = false

        try {
            geschreven = schrijf(gepubliceerd)
        } finally {
            if (!geschreven) {
                runCatching { deduplicatie.verwijder(gepubliceerd.eventId) }
                    .onFailure { log.warnf(it, "Vrijgeven idempotentie-marker faalde; TTL ruimt alsnog op") }
            }
        }
    }

    /** `true` als het bericht geschreven is; `false` bij geen actieve sessie (no-op). */
    private fun schrijf(event: GepubliceerdBerichtEvent): Boolean {
        // AVG art. 30: leg de ontvanger als data-subject vast nu we daadwerkelijk
        // diens berichtgegevens verwerken (niet bij duplicaat/validatiefout).
        logboekContext.dataSubjectType = event.ontvanger.type.name
        logboekContext.dataSubjectId = event.ontvanger.waarde

        // De CloudEvent draagt geen bijlage-metadata; die volgen bij de volgende
        // volledige ophaling. Een aanmeld-entry heeft dus aantalBijlagen=0.
        val bericht = Bericht(
            berichtId = event.berichtId,
            afzender = event.afzender.waarde,
            ontvanger = event.ontvanger.waarde,
            onderwerp = event.onderwerp,
            inhoud = event.inhoud,
            publicatietijdstip = event.publicatietijdstip,
            magazijnId = event.magazijnId,
            aantalBijlagen = 0,
        )

        try {
            sessiecache.schrijfBericht(event.ontvanger, bericht)

            return true
        } catch (ignored: SessiecacheException.GeenActieveSessie) {
            // Geen actieve sessie is het normale geval (de meeste ontvangers hebben er geen):
            // geaccepteerd-maar-overgeslagen, geen fout. Bewust geen detail loggen.
            log.debug("Geen actieve sessie voor ontvanger; aanmelding overgeslagen")

            return false
        } catch (e: SessiecacheException) {
            // Overige cache-fouten (ongeldige invoer, opslag onbereikbaar, onleesbaar)
            // status-behoudend naar de webhook-caller: naApiFout reproduceert dezelfde
            // status (400/503/500) die de facade voorheen zelf gooide.
            throw e.naApiFout()
        }
    }

    /**
     * Parseert en valideert de ruwe envelope in één stap naar een getypeerd event.
     * Elke schending (ontbrekend verplicht veld, verkeerde envelope-waarde, ongeldig
     * OIN/identificatienummer, onbekende bron) levert een 400. Magazijn-afleiding zit
     * hier zodat een onbekende bron al vóór de idempotentie-claim afketst.
     */
    private fun parse(event: AangemeldCloudEvent): GepubliceerdBerichtEvent {
        vereis(!event.id.isNullOrBlank(), "CloudEvent mist 'id'.")
        vereis(event.specversion == SPEC_VERSION, "Niet-ondersteunde CloudEvents specversion.")
        vereis(event.source?.startsWith(NLD_NAMESPACE) == true, "CloudEvent 'source' moet de urn:nld: namespace gebruiken.")
        vereis(event.type == EVENT_TYPE, "Onverwacht CloudEvent-type.")

        val data = event.data ?: throw badRequest("CloudEvent mist 'data'.")

        val berichtId = data.berichtId ?: throw badRequest("data.berichtId ontbreekt.")
        vereis(!data.afzender.isNullOrBlank(), "data.afzender ontbreekt.")
        val ontvangerDto = data.ontvanger ?: throw badRequest("data.ontvanger ontbreekt.")
        vereis(!data.onderwerp.isNullOrBlank(), "data.onderwerp ontbreekt.")
        vereis(!data.inhoud.isNullOrBlank(), "data.inhoud ontbreekt.")
        val publicatietijdstip = data.publicatietijdstip ?: throw badRequest("data.publicatietijdstip ontbreekt.")

        val afzender = parseAfzender(data.afzender!!)
        val magazijnId = afzenderIndex.magazijnVoor(afzender)
            ?: throw badRequest("Afzender hoort bij geen geconfigureerd magazijn.")

        return GepubliceerdBerichtEvent(
            eventId = event.id!!,
            berichtId = berichtId,
            afzender = afzender,
            ontvanger = parseOntvanger(ontvangerDto),
            magazijnId = magazijnId,
            onderwerp = data.onderwerp!!,
            inhoud = data.inhoud!!,
            publicatietijdstip = publicatietijdstip,
        )
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
            ?: throw badRequest("Onbekend of ontbrekend identificatienummer-type voor ontvanger.")

        val waarde = ontvanger.waarde
        vereis(!waarde.isNullOrBlank(), "data.ontvanger.waarde ontbreekt.")

        try {
            return Identificatienummer.of(type, waarde!!)
        } catch (e: DomainValidationException) {
            throw badRequest("Ontvanger is geen geldig identificatienummer.", e)
        }
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
