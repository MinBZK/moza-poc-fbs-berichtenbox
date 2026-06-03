package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtenLijst
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

/**
 * Thin pass-through naar de sessiecache voor lijst- en zoekoperaties. SSE-
 * passthrough (`_ophalen`) wordt in [SsePassthroughResource] geregeld
 * omdat de Quarkus REST-client voor SSE een `Multi<T>`-signatuur nodig heeft
 * die niet in deze synchrone client past.
 *
 * Bewuste trade-off: de lees-paden deserialiseren de sessiecache-JSON naar de
 * gegenereerde DTO's en serialiseren die vrijwel ongewijzigd opnieuw (idem in
 * [BerichtOphaalService.haalBericht]). Dat kost een extra Jackson-round-trip per
 * read, maar levert contract-validatie tegen de spec én de HAL-link-vertaling
 * ([vertaalPagineringLinks]) "gratis". Berichten zijn enkele KB's (zie CLAUDE.md),
 * dus de winst van rauwe byte-passthrough weegt niet op tegen het verlies van die
 * twee garanties; herzie pas bij een meetbare load-aanleiding.
 */
@ApplicationScoped
class BerichtenlijstService(
    @RestClient private val sessiecache: SessiecacheClient,
) {
    fun lijst(xOntvanger: String, pagina: Int?, paginaGrootte: Int?): BerichtenLijst =
        vertaalPagineringLinks(mapUpstreamFout(log, "cache-lijst") { sessiecache.lijst(xOntvanger, pagina, paginaGrootte) })

    fun zoek(xOntvanger: String, q: String): BerichtenLijst =
        vertaalPagineringLinks(mapUpstreamFout(log, "cache-zoek") { sessiecache.zoek(xOntvanger, q) })

    // De sessiecache levert HAL-paginering-links met haar eigen query-parameters
    // (`page`/`pageSize`); dit endpoint adverteert `pagina`/`paginaGrootte`. Zonder
    // vertaling komt een client die `_links.next` volgt op de verkeerde parameter-
    // namen uit en krijgt hij altijd pagina 0 terug. Paden zijn al gelijk
    // (`/api/v1/berichten`), dus alleen de parameternamen worden herschreven.
    private fun vertaalPagineringLinks(lijst: BerichtenLijst): BerichtenLijst {
        lijst.links?.let { links ->
            links.self?.let { it.href = vertaalParams(it.href) }
            links.next?.let { it.href = vertaalParams(it.href) }
            links.prev?.let { it.href = vertaalParams(it.href) }
        }

        return lijst
    }

    // Anker op de query-param-grens (`?`/`&`) zodat alleen een echte parameternaam
    // wordt herschreven en niet een toevallige substring (bv. een `homepage=`-waarde).
    private fun vertaalParams(href: String?): String? =
        href?.replace(PAGE_SIZE_PARAM, "$1paginaGrootte=")
            ?.replace(PAGE_PARAM, "$1pagina=")

    private companion object {
        private val log: Logger = Logger.getLogger(BerichtenlijstService::class.java)

        // Vooraf gecompileerd: vertaalParams draait op elke lijst/zoek-response;
        // per-call `Regex(...)` zou de patronen telkens opnieuw compileren.
        private val PAGE_SIZE_PARAM = Regex("([?&])pageSize=")
        private val PAGE_PARAM = Regex("([?&])page=")
    }
}
