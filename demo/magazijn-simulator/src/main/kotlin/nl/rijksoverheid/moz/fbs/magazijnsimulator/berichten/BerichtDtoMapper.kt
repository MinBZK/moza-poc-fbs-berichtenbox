package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import jakarta.ws.rs.core.UriBuilder
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BerichtLinks
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BerichtResponse
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BerichtSamenvatting
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BerichtStatusInfo
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BerichtenLijst
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BijlageLinks
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BijlageMetadata
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BijlageSamenvatting
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.Link
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.PaginationLinks
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Bericht
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BerichtStatus
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BerichtenPagina
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.Identificatie
import java.util.UUID
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.Bericht as BerichtDto
import nl.rijksoverheid.moz.fbs.magazijnsimulator.opslag.BijlageMetadata as BijlageMetadataDomein
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.Identificatienummer as IdentificatienummerDto

/**
 * Vertaalt de domeinobjecten naar de gegenereerde API-modellen en bouwt de HAL-links op een
 * meegegeven basis; de resources halen die uit
 * [nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnPad.basisUri], zodat elke link het
 * magazijn-prefix draagt.
 *
 * Pure functies, geen state — het object mag gedeeld worden.
 */
object BerichtDtoMapper {

    fun naarBericht(bericht: Bericht, basis: UriBuilder): BerichtDto = BerichtDto().apply {
        berichtId = bericht.berichtId
        afzender = bericht.afzender.waarde
        ontvanger = naarIdentificatienummer(bericht.ontvanger)
        onderwerp = bericht.onderwerp
        inhoud = bericht.inhoud
        tijdstipOntvangst = bericht.tijdstipOntvangst
        publicatietijdstip = bericht.publicatietijdstip
        bijlagen = bericht.bijlagen.map { naarBijlageMetadata(it, bericht.berichtId, basis) }
        status = bericht.status?.let { naarStatusInfo(it) }
        links = BerichtLinks().apply { self = link(berichtHref(bericht.berichtId, basis)) }
    }

    fun naarBerichtResponse(bericht: Bericht, basis: UriBuilder): BerichtResponse = BerichtResponse().apply {
        berichtId = bericht.berichtId
        afzender = bericht.afzender.waarde
        ontvanger = naarIdentificatienummer(bericht.ontvanger)
        onderwerp = bericht.onderwerp
        tijdstipOntvangst = bericht.tijdstipOntvangst
        publicatietijdstip = bericht.publicatietijdstip
        links = BerichtLinks().apply { self = link(berichtHref(bericht.berichtId, basis)) }
    }

    fun naarBerichtenLijst(pagina: BerichtenPagina, afzender: String?, basis: UriBuilder): BerichtenLijst =
        BerichtenLijst().apply {
            berichten = pagina.berichten.map { naarSamenvatting(it, basis) }
            page = pagina.page
            pageSize = pagina.pageSize
            totalElements = pagina.totalElements
            totalPages = pagina.totalPages
            links = pagineerLinks(pagina, afzender, basis)
        }

    /**
     * De samenvatting draagt wél de volledige `inhoud` en een lichte bijlage-lijst (alleen id en
     * naam). Dat is wat het schema `BerichtSamenvatting` voorschrijft; de beschrijving bij de
     * operatie beweert het tegendeel, maar het schema is leidend en het echte magazijn volgt het
     * schema. Hier afwijken zou de simulator herkenbaar maken.
     */
    private fun naarSamenvatting(bericht: Bericht, basis: UriBuilder): BerichtSamenvatting =
        BerichtSamenvatting().apply {
            berichtId = bericht.berichtId
            afzender = bericht.afzender.waarde
            ontvanger = naarIdentificatienummer(bericht.ontvanger)
            onderwerp = bericht.onderwerp
            inhoud = bericht.inhoud
            tijdstipOntvangst = bericht.tijdstipOntvangst
            publicatietijdstip = bericht.publicatietijdstip
            aantalBijlagen = bericht.bijlagen.size
            bijlagen = bericht.bijlagen.map { meta ->
                BijlageSamenvatting().apply {
                    bijlageId = meta.bijlageId
                    naam = meta.naam
                }
            }
            status = bericht.status?.let { naarStatusInfo(it) }
            links = BerichtLinks().apply { self = link(berichtHref(bericht.berichtId, basis)) }
        }

    private fun naarBijlageMetadata(
        meta: BijlageMetadataDomein,
        berichtId: UUID,
        basis: UriBuilder,
    ): BijlageMetadata = BijlageMetadata().apply {
        bijlageId = meta.bijlageId
        naam = meta.naam
        mimeType = meta.mimeType
        links = BijlageLinks().apply { self = link(bijlageHref(berichtId, meta.bijlageId, basis)) }
    }

    private fun naarIdentificatienummer(identificatie: Identificatie): IdentificatienummerDto =
        IdentificatienummerDto().apply {
            type = IdentificatienummerDto.TypeEnum.valueOf(identificatie.type.name)
            waarde = identificatie.waarde
        }

    private fun naarStatusInfo(status: BerichtStatus): BerichtStatusInfo = BerichtStatusInfo().apply {
        gelezen = status.gelezen
        map = status.map
        gewijzigdOp = status.gewijzigdOp
    }

    /**
     * `self`, `first` en `last` staan er altijd; `prev` en `next` alleen als die pagina bestaat.
     * Zonder berichten is `totalPages` nul en wijst `last` naar pagina 0 — precies wat het echte
     * magazijn doet, en een detail dat nergens in de spec staat.
     */
    private fun pagineerLinks(
        pagina: BerichtenPagina,
        afzender: String?,
        basis: UriBuilder,
    ): PaginationLinks = PaginationLinks().apply {
        // De `X-Ontvanger`-header is bewust GEEN onderdeel van de URL — persoonsgegevens horen niet
        // in HAL-links of toegangslogs.
        val voorPagina: (Int) -> Link = { p -> link(lijstHref(p, pagina.pageSize, afzender, basis)) }

        self = voorPagina(pagina.page)
        first = voorPagina(0)
        last = voorPagina(maxOf(0, pagina.totalPages - 1))

        if (pagina.page > 0) {
            prev = voorPagina(pagina.page - 1)
        }

        if (pagina.page < pagina.totalPages - 1) {
            next = voorPagina(pagina.page + 1)
        }
    }

    private fun lijstHref(page: Int, pageSize: Int, afzender: String?, basis: UriBuilder): String {
        var builder = basis.clone()
            .path("berichten")
            .queryParam("page", page)
            .queryParam("pageSize", pageSize)

        if (afzender != null) {
            builder = builder.queryParam("afzender", afzender)
        }

        return builder.build().toString()
    }

    private fun berichtHref(berichtId: UUID, basis: UriBuilder): String =
        basis.clone().path("berichten").path(berichtId.toString()).build().toString()

    private fun bijlageHref(berichtId: UUID, bijlageId: UUID, basis: UriBuilder): String =
        basis.clone()
            .path("berichten")
            .path(berichtId.toString())
            .path("bijlagen")
            .path(bijlageId.toString())
            .build()
            .toString()

    private fun link(href: String): Link = Link().apply { this.href = href }
}
