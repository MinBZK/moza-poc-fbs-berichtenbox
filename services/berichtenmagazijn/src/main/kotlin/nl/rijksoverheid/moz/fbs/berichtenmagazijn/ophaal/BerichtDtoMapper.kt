package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import jakarta.ws.rs.core.UriBuilder
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtLinks
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtSamenvatting
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtStatusInfo
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BerichtenLijst
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BijlageLinks
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BijlageMetadata
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.BijlageSamenvatting
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Identificatienummer as IdentificatienummerDto
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Link
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.PaginationLinks
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.api.model.Bericht as BerichtDto
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.Bericht
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BerichtStatus
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.PagedBerichten
import nl.rijksoverheid.moz.fbs.berichtenmagazijn.opslag.BijlageMetadata as DomainBijlageMetadata
import java.util.UUID

/**
 * Mapt domeinobjecten naar de gegenereerde API-modellen. Bouwt HAL-links op
 * basis van een meegegeven `baseUri` (typisch [jakarta.ws.rs.core.UriInfo.getBaseUriBuilder]).
 *
 * Het mapper-object houdt geen state aan; alle methodes zijn pure functies.
 * Daardoor kan het gerust gedeeld worden tussen Ophaal- en Beheer-resources.
 */
internal object BerichtDtoMapper {

    fun toBericht(bericht: Bericht, baseUri: UriBuilder): BerichtDto =
        BerichtDto().apply {
            berichtId = bericht.berichtId
            afzender = bericht.afzender.waarde
            ontvanger = toIdentificatienummerDto(bericht.ontvanger)
            onderwerp = bericht.onderwerp
            inhoud = bericht.inhoud
            tijdstipOntvangst = bericht.tijdstipOntvangst
            publicatietijdstip = bericht.publicatietijdstip
            bijlagen = bericht.bijlagen.map { toBijlageMetadataDto(it, bericht.berichtId, baseUri) }
            status = bericht.status?.let { toStatusDto(it) }
            links = BerichtLinks().apply {
                self = Link().apply { href = selfHrefVoorBericht(bericht.berichtId, baseUri) }
            }
        }

    fun toBerichtenLijst(
        pagina: PagedBerichten,
        afzender: String?,
        baseUri: UriBuilder,
    ): BerichtenLijst = BerichtenLijst().apply {
        berichten = pagina.berichten.map { toBerichtSamenvatting(it, baseUri) }
        page = pagina.page
        pageSize = pagina.pageSize
        totalElements = pagina.totalElements
        totalPages = pagina.totalPages
        links = pagineerLinks(pagina, afzender, baseUri)
    }

    private fun toBerichtSamenvatting(bericht: Bericht, baseUri: UriBuilder): BerichtSamenvatting =
        BerichtSamenvatting().apply {
            berichtId = bericht.berichtId
            afzender = bericht.afzender.waarde
            ontvanger = toIdentificatienummerDto(bericht.ontvanger)
            onderwerp = bericht.onderwerp
            inhoud = bericht.inhoud
            tijdstipOntvangst = bericht.tijdstipOntvangst
            publicatietijdstip = bericht.publicatietijdstip
            aantalBijlagen = bericht.bijlagen.size
            bijlagen = bericht.bijlagen.map { toBijlageSamenvattingDto(it) }
            status = bericht.status?.let { toStatusDto(it) }
            links = BerichtLinks().apply {
                self = Link().apply { href = selfHrefVoorBericht(bericht.berichtId, baseUri) }
            }
        }

    private fun toBijlageSamenvattingDto(meta: DomainBijlageMetadata): BijlageSamenvatting =
        BijlageSamenvatting().apply {
            bijlageId = meta.bijlageId
            naam = meta.naam
        }

    private fun toBijlageMetadataDto(
        meta: DomainBijlageMetadata,
        berichtId: UUID,
        baseUri: UriBuilder,
    ): BijlageMetadata = BijlageMetadata().apply {
        bijlageId = meta.bijlageId
        naam = meta.naam
        mimeType = meta.mimeType
        links = BijlageLinks().apply {
            self = Link().apply { href = bijlageHref(berichtId, meta.bijlageId, baseUri) }
        }
    }

    private fun toIdentificatienummerDto(id: Identificatienummer): IdentificatienummerDto =
        IdentificatienummerDto().apply {
            type = IdentificatienummerDto.TypeEnum.valueOf(id.type.name)
            waarde = id.waarde
        }

    private fun toStatusDto(status: BerichtStatus): BerichtStatusInfo =
        BerichtStatusInfo().apply {
            gelezen = status.gelezen
            map = status.map
            gewijzigdOp = status.gewijzigdOp
        }

    private fun pagineerLinks(
        pagina: PagedBerichten,
        afzender: String?,
        baseUri: UriBuilder,
    ): PaginationLinks = PaginationLinks().apply {
        // De `X-Ontvanger`-header is bewust GEEN onderdeel van de URL — PII hoort
        // niet in HAL-links of toegangslogs.
        val builder: (Int) -> Link = { p -> linkVoorPagina(p, pagina.pageSize, afzender, baseUri) }
        self = builder(pagina.page)
        first = builder(0)
        // totalPages is 0 als er geen berichten zijn — laatste pagina = 0 i.p.v. -1.
        last = builder(maxOf(0, pagina.totalPages - 1))
        if (pagina.page > 0) {
            prev = builder(pagina.page - 1)
        }
        if (pagina.page < pagina.totalPages - 1) {
            next = builder(pagina.page + 1)
        }
    }

    private fun linkVoorPagina(page: Int, pageSize: Int, afzender: String?, baseUri: UriBuilder): Link {
        var builder = baseUri.clone()
            .path(ApiInfo.BASE_PATH)
            .path("berichten")
            .queryParam("page", page)
            .queryParam("pageSize", pageSize)
        if (afzender != null) {
            builder = builder.queryParam("afzender", afzender)
        }
        return Link().apply { href = builder.build() }
    }

    private fun selfHrefVoorBericht(berichtId: UUID, baseUri: UriBuilder) = baseUri.clone()
        .path(ApiInfo.BASE_PATH)
        .path("berichten")
        .path(berichtId.toString())
        .build()

    private fun bijlageHref(berichtId: UUID, bijlageId: UUID, baseUri: UriBuilder) = baseUri.clone()
        .path(ApiInfo.BASE_PATH)
        .path("berichten")
        .path(berichtId.toString())
        .path("bijlagen")
        .path(bijlageId.toString())
        .build()
}
