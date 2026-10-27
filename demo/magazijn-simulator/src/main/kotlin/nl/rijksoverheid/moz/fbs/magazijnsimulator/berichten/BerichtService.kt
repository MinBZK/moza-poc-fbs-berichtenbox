package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.kern.exception.requireValid
import nl.rijksoverheid.moz.fbs.kern.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.Foutcode
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.SimulatorFout
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.Gedrag
import nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag.GedragModus
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnContext
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Bericht
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BerichtRepository
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BerichtStatusWijziging
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BerichtenPagina
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Bijlage
import org.jboss.logging.Logger
import java.time.Clock
import java.util.UUID

/**
 * De zes operaties van de spec, uitgevoerd op het magazijn dat het pad-filter heeft gekozen.
 *
 * Wat hier telt is niet dat het werkt, maar dat het **hetzelfde** werkt als het echte magazijn: de
 * volgorde van 403, 404 en 410, de merge-patch-semantiek, en dat een tweede `DELETE` gewoon opnieuw
 * slaagt. Die regels staan nergens in de spec — een tweede implementatie die ze zelf verzint, wijkt
 * stilzwijgend af, en dan demonstreert de demo iets dat in werkelijkheid anders gaat.
 */
@ApplicationScoped
class BerichtService(
    private val repository: BerichtRepository,
    private val magazijnContext: MagazijnContext,
    private val clock: Clock,
) {

    /**
     * Een [GedragModus.ONVOLLEDIG]-magazijn telt een archief mee dat de lijst niet levert. Alleen als
     * er voor deze ontvanger iets staat: wie hier geen post heeft, krijgt geen "0 van 480".
     */
    fun lijst(ontvanger: Identificatienummer, afzender: String?, page: Int, pageSize: Int): BerichtenPagina {
        val pagina = repository.lijst(magazijnDbId(), ontvanger, afzender, page, pageSize)
        val onvolledig = magazijnContext.magazijn.gedrag.modus == GedragModus.ONVOLLEDIG

        if (!onvolledig || pagina.totalElements == 0L) return pagina

        return pagina.copy(totalElements = pagina.totalElements + Gedrag.ONVOLLEDIG_ARCHIEF)
    }

    /**
     * Eerst bestaan, dan mogen. Een verwijderd of onbekend bericht is een 404 nog vóór de
     * ontvanger-check; pas als het bericht er is, kan het antwoord 403 worden.
     */
    fun haalOp(berichtId: UUID, ontvanger: Identificatienummer): Bericht {
        val bericht = repository.zoek(magazijnDbId(), berichtId) ?: throw nietGevonden(berichtId)

        vereisOntvanger(bericht, ontvanger)

        return bericht
    }

    /**
     * Voor de tweede ingang: andermans, verwijderd en onbekend zijn allemaal hetzelfde `404`, zodat
     * een app niet kan aftasten welke bericht-id's bestaan.
     */
    fun haalOpVoorOntvanger(berichtId: UUID, ontvanger: Identificatienummer): Bericht =
        repository.zoekVoorOntvanger(magazijnDbId(), berichtId, ontvanger) ?: throw nietGevonden(berichtId)

    /**
     * De autorisatie hangt aan het bericht, niet aan de bijlage: eerst het bericht ophalen (404 of
     * 403), dan pas de bijlage zoeken. Een bestaande bijlage-id onder een ánder bericht levert
     * daardoor ook een 404 op, in plaats van bytes uit een bericht van iemand anders.
     */
    fun haalBijlageOp(berichtId: UUID, bijlageId: UUID, ontvanger: Identificatienummer): Bijlage {
        haalOp(berichtId, ontvanger)

        return repository.zoekBijlage(magazijnDbId(), berichtId, bijlageId)
            ?: throw SimulatorFout(
                Foutcode.BERICHT_ONBEKEND,
                Response.Status.NOT_FOUND,
                "Bijlage $bijlageId bestaat niet bij bericht $berichtId",
            )
    }

    /**
     * Werkt de status bij en geeft het volledige bericht terug.
     *
     * De volgorde is hier bewust anders dan bij [haalOp]: eerst wordt gekeken of het bericht
     * bestaat — óók als het al verwijderd is — dan of de aanroeper de ontvanger is, en pas daarna of
     * het verwijderd is. Zo levert andermans verwijderde bericht een 403 op en niet een 404, want
     * uit dat verschil zou af te leiden zijn welke bericht-id's bestaan. Het eigen verwijderde
     * bericht komt daardoor pas ná de eigenaar-check langs, en mag dus een 410 krijgen.
     */
    fun wijzigStatus(berichtId: UUID, ontvanger: Identificatienummer, wijziging: BerichtStatusWijziging): Bericht {
        requireValid(!wijziging.isLeeg) { "Patch moet minstens een van 'gelezen' of 'map' bevatten" }

        val bestaand = repository.zoekInclusiefVerwijderd(magazijnDbId(), berichtId) ?: throw nietGevonden(berichtId)

        vereisOntvanger(bestaand.bericht, ontvanger)

        // 410 en niet 404: de eigenaar-check hierboven slaagde, dus de aanroeper mag weten dat dit
        // bericht bestond — hij gooide het zelf weg. Andermans verwijderde bericht strandt op 403
        // en bereikt deze regel niet.
        if (bestaand.isVerwijderd) throw alVerwijderd(berichtId)

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
    fun verwijder(berichtId: UUID, ontvanger: Identificatienummer) {
        val bestaand = repository.zoekInclusiefVerwijderd(magazijnDbId(), berichtId) ?: throw nietGevonden(berichtId)

        vereisOntvanger(bestaand.bericht, ontvanger)

        if (bestaand.isVerwijderd) return

        val verwijderd = repository.softDelete(magazijnDbId(), berichtId, ontvanger, clock.instant())

        if (!verwijderd) {
            // Niets geraakt terwijl het bericht er net nog actief stond: iemand anders was net iets
            // eerder. Het antwoord blijft 204 — de ontvanger krijgt wat hij vroeg — maar stil
            // doorgaan zou een echte fout in de discriminator net zo onzichtbaar maken.
            log.infof("Verwijderen raakte geen rij; bericht %s was al weg. magazijn=%s", berichtId, magazijnDbId())
        }
    }

    /**
     * Slaat een aangeleverd bericht op. Het magazijn kent zelf het `berichtId` toe en zet zelf het
     * tijdstip van ontvangst; ontbreekt een publicatietijdstip, dan is dat exact hetzelfde moment.
     */
    fun leverAan(
        afzender: Oin,
        ontvanger: Identificatienummer,
        onderwerp: String,
        inhoud: String,
        publicatietijdstip: java.time.Instant?,
        bijlagen: List<Bijlage>,
    ): Bericht {
        // De vorm van naam en MIME-type wordt hier getoetst en niet in `Bijlage` zelf: dat type komt
        // ook uit de opslag, en een rij die een latere regel niet haalt hoort geen clientfout te
        // worden. Dit is de weg waarlangs invoer binnenkomt, dus hier hoort de 400.
        bijlagen.forEach { Bijlage.valideerVorm(it.naam, it.mimeType) }

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
    private fun vereisOntvanger(bericht: Bericht, ontvanger: Identificatienummer) {
        if (bericht.ontvanger != ontvanger) {
            throw ForbiddenException("Geen toegang tot dit bericht")
        }
    }

    private fun nietGevonden(berichtId: UUID) = SimulatorFout(
        Foutcode.BERICHT_ONBEKEND,
        Response.Status.NOT_FOUND,
        "Bericht $berichtId bestaat niet in magazijn ${magazijnContext.magazijn.oin}",
    )

    private fun alVerwijderd(berichtId: UUID) = SimulatorFout(
        Foutcode.BERICHT_VERWIJDERD,
        Response.Status.GONE,
        "Bericht $berichtId is verwijderd in magazijn ${magazijnContext.magazijn.oin}",
    )

    private fun magazijnDbId(): Long = magazijnContext.magazijn.dbId

    private companion object {
        private val log: Logger = Logger.getLogger(BerichtService::class.java)
    }
}
