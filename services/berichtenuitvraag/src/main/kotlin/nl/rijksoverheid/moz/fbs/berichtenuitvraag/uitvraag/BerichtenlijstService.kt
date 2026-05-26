package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtenLijst
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Thin pass-through naar de sessiecache voor lijst- en zoekoperaties. SSE-
 * passthrough (`_ophalen`) wordt in [SsePassthroughResource] geregeld (Task 9)
 * omdat de Quarkus REST-client voor SSE een `Multi<T>`-signatuur nodig heeft
 * die niet in deze synchrone client past.
 */
@ApplicationScoped
class BerichtenlijstService(
    @RestClient private val sessiecache: SessiecacheClient,
) {
    fun lijst(xOntvanger: String, map: String?, pagina: Int?, paginaGrootte: Int?): BerichtenLijst =
        sessiecache.lijst(xOntvanger, map, pagina, paginaGrootte)

    fun zoek(xOntvanger: String, q: String, map: String?): BerichtenLijst =
        sessiecache.zoek(xOntvanger, q, map)
}
