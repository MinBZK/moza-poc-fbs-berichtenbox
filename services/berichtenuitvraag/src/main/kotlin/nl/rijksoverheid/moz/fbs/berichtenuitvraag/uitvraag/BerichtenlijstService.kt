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
 */
@ApplicationScoped
class BerichtenlijstService(
    @RestClient private val sessiecache: SessiecacheClient,
) {
    fun lijst(xOntvanger: String, map: String?, pagina: Int?, paginaGrootte: Int?): BerichtenLijst =
        vertaalPaginatieLinks(mapUpstreamFout(log, "cache-lijst") { sessiecache.lijst(xOntvanger, map, pagina, paginaGrootte) })

    fun zoek(xOntvanger: String, q: String, map: String?): BerichtenLijst =
        vertaalPaginatieLinks(mapUpstreamFout(log, "cache-zoek") { sessiecache.zoek(xOntvanger, q, map) })

    // De sessiecache levert HAL-paginatie-links met haar eigen query-parameters
    // (`page`/`pageSize`); dit endpoint adverteert `pagina`/`paginaGrootte`. Zonder
    // vertaling komt een client die `_links.next` volgt op de verkeerde parameter-
    // namen uit en krijgt hij altijd pagina 0 terug. Paden zijn al gelijk
    // (`/api/v1/berichten`), dus alleen de parameternamen worden herschreven.
    private fun vertaalPaginatieLinks(lijst: BerichtenLijst): BerichtenLijst {
        lijst.links?.let { links ->
            links.self?.let { it.href = vertaalParams(it.href) }
            links.next?.let { it.href = vertaalParams(it.href) }
            links.prev?.let { it.href = vertaalParams(it.href) }
        }

        return lijst
    }

    private fun vertaalParams(href: String?): String? =
        href?.replace("pageSize=", "paginaGrootte=")?.replace("page=", "pagina=")

    private companion object {
        private val log: Logger = Logger.getLogger(BerichtenlijstService::class.java)
    }
}
