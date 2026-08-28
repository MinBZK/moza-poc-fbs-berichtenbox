package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnContext
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Bericht
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BerichtStatusWijziging
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BerichtenPagina
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Bijlage
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Identificatie
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.vereis
import java.time.Clock
import java.util.UUID

/**
 * De zes operaties van de spec, uitgevoerd op het magazijn dat het pad-filter heeft gekozen.
 *
 * Wat hier telt is niet dat het werkt, maar dat het **hetzelfde** werkt als het echte magazijn: de
 * volgorde van 403 en 404, de merge-patch-semantiek, en dat een tweede `DELETE` gewoon opnieuw
 * slaagt. Die regels staan nergens in de spec — een tweede implementatie die ze zelf verzint, wijkt
 * stilzwijgend af, en dan demonstreert de demo iets dat in werkelijkheid anders gaat.
 */
@ApplicationScoped
class BerichtService(
    private val repository: BerichtRepository,
    private val magazijnContext: MagazijnContext,
    private val clock: Clock,
) {

    fun lijst(ontvanger: Identificatie, afzender: String?, page: Int, pageSize: Int): BerichtenPagina =
        repository.lijst(magazijnDbId(), ontvanger, afzender, page, pageSize)

    /**
     * Eerst bestaan, dan mogen. Een verwijderd of onbekend bericht is een 404 nog vóór de
     * ontvanger-check; pas als het bericht er is, kan het antwoord 403 worden.
     */
    fun haalOp(berichtId: UUID, ontvanger: Identificatie): Bericht {
        val bericht = repository.zoek(magazijnDbId(), berichtId) ?: throw nietGevonden(berichtId)

        vereisOntvanger(bericht, ontvanger)

        return bericht
    }

    /**
     * De autorisatie hangt aan het bericht, niet aan de bijlage: eerst het bericht ophalen (404 of
     * 403), dan pas de bijlage zoeken. Een bestaande bijlage-id onder een ánder bericht levert
     * daardoor ook een 404 op, in plaats van bytes uit een bericht van iemand anders.
     */
    fun haalBijlageOp(berichtId: UUID, bijlageId: UUID, ontvanger: Identificatie): Bijlage {
        haalOp(berichtId, ontvanger)

        return repository.zoekBijlage(magazijnDbId(), berichtId, bijlageId)
            ?: throw NotFoundException("Bijlage $bijlageId bestaat niet bij bericht $berichtId")
    }

    /**
     * Werkt de status bij en geeft het volledige bericht terug.
     *
     * De volgorde is hier bewust anders dan bij [haalOp]: eerst wordt gekeken of het bericht
     * bestaat — óók als het al verwijderd is — dan of de aanroeper de ontvanger is, en pas daarna of
     * het verwijderd is. Zo levert andermans verwijderde bericht een 403 op en niet een 404, want
     * uit dat verschil zou af te leiden zijn welke bericht-id's bestaan.
     */
    fun wijzigStatus(berichtId: UUID, ontvanger: Identificatie, wijziging: BerichtStatusWijziging): Bericht {
        vereis(!wijziging.isLeeg) { "Patch moet minstens een van 'gelezen' of 'map' bevatten" }

        val bestaand = repository.zoekInclusiefVerwijderd(magazijnDbId(), berichtId) ?: throw nietGevonden(berichtId)

        vereisOntvanger(bestaand.bericht, ontvanger)

        if (bestaand.isVerwijderd) throw nietGevonden(berichtId)

        return repository.wijzigStatus(magazijnDbId(), berichtId, wijziging, clock.instant())
            // Alleen bereikbaar als het bericht tussen de twee stappen door verdwijnt; dan is 404
            // het juiste antwoord en niet een 500.
            ?: throw nietGevonden(berichtId)
    }

    /**
     * Soft-delete, en idempotent: een tweede `DELETE` door dezelfde ontvanger slaagt opnieuw
     * (RFC 9110 §9.3.5). Andermans bericht geeft 403, ook als het al verwijderd is — zie
     * [wijzigStatus] voor waarom dat geen 404 mag worden.
     */
    fun verwijder(berichtId: UUID, ontvanger: Identificatie) {
        val bestaand = repository.zoekInclusiefVerwijderd(magazijnDbId(), berichtId) ?: throw nietGevonden(berichtId)

        vereisOntvanger(bestaand.bericht, ontvanger)

        if (bestaand.isVerwijderd) return

        repository.softDelete(magazijnDbId(), berichtId, ontvanger, clock.instant())
    }

    /**
     * Slaat een aangeleverd bericht op. Het magazijn kent zelf het `berichtId` toe en zet zelf het
     * tijdstip van ontvangst; ontbreekt een publicatietijdstip, dan is dat exact hetzelfde moment.
     */
    fun leverAan(
        afzender: Identificatie,
        ontvanger: Identificatie,
        onderwerp: String,
        inhoud: String,
        publicatietijdstip: java.time.Instant?,
        bijlagen: List<Bijlage>,
    ): Bericht {
        val ontvangen = clock.instant()
        val bericht = Bericht(
            berichtId = UUID.randomUUID(),
            afzender = afzender,
            ontvanger = ontvanger,
            onderwerp = onderwerp,
            inhoud = inhoud,
            tijdstipOntvangst = ontvangen,
            publicatietijdstip = publicatietijdstip ?: ontvangen,
            bijlagen = bijlagen.map { it.metadata() },
        )

        repository.bewaar(magazijnDbId(), bericht, bijlagen)

        return bericht
    }

    /**
     * De ontvanger-check op één plek. Type én waarde tellen mee: `RSIN:999993653` is een ander
     * identificatienummer dan `BSN:999993653`, ook al is de cijferreeks gelijk.
     *
     * De waarde blijft uit de log — die kan een BSN zijn. Het bericht-id en het type volstaan om een
     * geweigerde aanroep terug te vinden.
     */
    private fun vereisOntvanger(bericht: Bericht, ontvanger: Identificatie) {
        if (bericht.ontvanger != ontvanger) {
            throw ForbiddenException("Geen toegang tot dit bericht")
        }
    }

    private fun nietGevonden(berichtId: UUID) =
        NotFoundException("Bericht $berichtId bestaat niet in magazijn ${magazijnContext.magazijn.oin}")

    private fun magazijnDbId(): Long = magazijnContext.magazijn.dbId
}
