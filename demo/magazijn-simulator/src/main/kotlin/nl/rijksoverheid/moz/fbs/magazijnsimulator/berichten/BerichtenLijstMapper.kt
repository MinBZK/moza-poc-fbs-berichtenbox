package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import jakarta.ws.rs.core.UriBuilder
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BerichtenLijst
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.Link
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.PaginationLinks

/**
 * Bouwt de berichtenlijst en de bijbehorende HAL-links op een meegegeven `baseUri`; de resource
 * haalt die uit [nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnPad.basisUri], zodat de
 * links het magazijn-prefix dragen. Zolang de simulator nog niets opslaat is elke lijst leeg; de
 * paginering-semantiek is dezelfde die het echte magazijn bij een lege pagina teruggeeft, zodat een
 * consumer het verschil niet ziet.
 *
 * Pure functies, geen state — het object mag gedeeld worden.
 */
object BerichtenLijstMapper {

    fun leeg(page: Int, pageSize: Int, afzender: String?, baseUri: UriBuilder): BerichtenLijst =
        BerichtenLijst().apply {
            berichten = emptyList()
            this.page = page
            this.pageSize = pageSize
            totalElements = 0
            totalPages = 0
            links = pagineerLinks(page, pageSize, afzender, baseUri)
        }

    /**
     * `next` en `prev` blijven weg zolang er geen pagina naast staat; met `totalPages = 0` is
     * pagina 0 zowel de eerste als de laatste.
     */
    private fun pagineerLinks(
        page: Int,
        pageSize: Int,
        afzender: String?,
        baseUri: UriBuilder,
    ): PaginationLinks = PaginationLinks().apply {
        // De `X-Ontvanger`-header is bewust GEEN onderdeel van de URL — persoonsgegevens horen
        // niet in HAL-links of toegangslogs.
        self = linkVoorPagina(page, pageSize, afzender, baseUri)
        first = linkVoorPagina(0, pageSize, afzender, baseUri)
        last = linkVoorPagina(0, pageSize, afzender, baseUri)
    }

    private fun linkVoorPagina(page: Int, pageSize: Int, afzender: String?, baseUri: UriBuilder): Link {
        var builder = baseUri.clone()
            .path("berichten")
            .queryParam("page", page)
            .queryParam("pageSize", pageSize)

        if (afzender != null) {
            builder = builder.queryParam("afzender", afzender)
        }

        return Link().apply { href = builder.build().toString() }
    }
}
