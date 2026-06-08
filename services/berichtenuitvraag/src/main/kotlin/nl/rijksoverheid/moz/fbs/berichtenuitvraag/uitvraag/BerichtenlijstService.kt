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

/**
 * Lijst- en zoekoperaties via de in-process [Sessiecache]-facade. De facade
 * levert domein-types; deze service mapt naar de uitvraag-API-modellen en bouwt
 * de HAL-paginering-links met de uitvraag-parameternamen (`pagina`/`paginaGrootte`).
 *
 * [leesUitCache] blijft de fout-grens: een [SessiecacheException] wordt exhaustief
 * naar zijn status vertaald en daarna geldt dezelfde upstream-politiek als op het
 * magazijn — een storing (Redis-storing, mislukte ophaling, cache-corruptie) wordt
 * 502 (de cache is voor de portaal-client een upstream-bron, of die nu over REST of
 * in-process wordt aangesproken); een 409 (cache nog niet gevuld / ophalen bezig)
 * propageert ongewijzigd.
 */
@ApplicationScoped
class BerichtenlijstService(
    private val sessiecache: Sessiecache,
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
            berichten = resultaat.berichten.map { UitvraagDtoMapper.toApiSamenvatting(it) }
            links = PaginaLinks().apply {
                self = Link().apply { href = "${ApiInfo.BASE_PATH}/berichten/_zoeken?q=$encodedQ" }
            }
        }
    }

    private fun toBerichtenLijst(pagina: BerichtenPagina, maakHref: (Int) -> String): BerichtenLijst =
        BerichtenLijst().apply {
            berichten = pagina.berichten.map { UitvraagDtoMapper.toApiSamenvatting(it) }
            links = paginaLinks(pagina, maakHref)
        }

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
