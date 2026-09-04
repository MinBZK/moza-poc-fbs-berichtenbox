package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtensessiecache.Sessiecache
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtenPagina
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.ApiInfo
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtenLijst
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Link
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.PaginaLinks
import nl.rijksoverheid.moz.fbs.common.identificatie.Identificatienummer
import org.jboss.logging.Logger
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.BerichtSamenvatting as DomeinSamenvatting

/**
 * Lijst- en zoekoperaties via de in-process [Sessiecache]-facade. De facade
 * levert domein-types; deze service mapt naar de uitvraag-API-modellen en bouwt
 * de HAL-paginering-links met de uitvraag-parameternamen (`pagina`/`paginaGrootte`).
 *
 * [leesUitCache] blijft de fout-grens: de cache classificeert zijn eigen uitkomst en die status
 * gaat rechtstreeks naar de client. Een onbereikbare opslag en een mislukte ophaalronde worden
 * 503 (opnieuw proberen heeft zin, met `Retry-After`), onleesbare cache-data 500 (dat helpt
 * niet), en een 409 (nog niet gevuld / ophalen bezig) propageert ongewijzigd. De 502-politiek
 * geldt alleen nog voor fouten die niet uit die classificatie komen, zoals een transport-fout.
 */
@ApplicationScoped
class BerichtenlijstService(
    private val sessiecache: Sessiecache,
    private val afzendernamen: Afzendernamen,
) {
    fun lijst(xOntvanger: String, pagina: Int?, paginaGrootte: Int?): BerichtenLijst {
        val ontvanger = Identificatienummer.fromHeader(xOntvanger)
        val resultaat = leesUitCache(log, "cache-lijst") { sessiecache.lijst(ontvanger, pagina, paginaGrootte) }

        return toBerichtenLijst(resultaat) { p -> "${ApiInfo.BASE_PATH}/berichten?pagina=$p&paginaGrootte=${resultaat.pageSize}" }
    }

    fun zoek(xOntvanger: String, q: String): BerichtenLijst {
        val ontvanger = Identificatienummer.fromHeader(xOntvanger)
        val resultaat = leesUitCache(log, "cache-zoek") { sessiecache.zoek(ontvanger, q) }
        val encodedQ = URLEncoder.encode(q, StandardCharsets.UTF_8)

        // `_zoeken` kent geen paginering-parameters in de uitvraag-spec; alleen een
        // self-link. De facade levert de eerste pagina (default-grootte).
        return BerichtenLijst().apply {
            berichten = resultaat.berichten.map { toApiSamenvatting(it) }
            links = PaginaLinks().apply {
                self = Link().apply { href = "${ApiInfo.BASE_PATH}/berichten/_zoeken?q=$encodedQ" }
            }
        }
    }

    private fun toBerichtenLijst(pagina: BerichtenPagina, maakHref: (Int) -> String): BerichtenLijst =
        BerichtenLijst().apply {
            berichten = pagina.berichten.map { toApiSamenvatting(it) }
            links = paginaLinks(pagina, maakHref)
        }

    private fun toApiSamenvatting(samenvatting: DomeinSamenvatting) =
        UitvraagDtoMapper.toApiSamenvatting(
            samenvatting,
            afzendernamen.naamVoor(samenvatting.magazijnId, samenvatting.afzenderNaam),
        )

    private fun paginaLinks(pagina: BerichtenPagina, maakHref: (Int) -> String): PaginaLinks {
        val links = PaginaLinks()
        links.self = Link().apply { href = maakHref(pagina.page) }

        if (pagina.page > 0) {
            links.prev = Link().apply { href = maakHref(pagina.page - 1) }
        }

        if (pagina.page < pagina.totalPages - 1) {
            links.next = Link().apply { href = maakHref(pagina.page + 1) }
        }

        return links
    }

    private companion object {
        private val log: Logger = Logger.getLogger(BerichtenlijstService::class.java)
    }
}
