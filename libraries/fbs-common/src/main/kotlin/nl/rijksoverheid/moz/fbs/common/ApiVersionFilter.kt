package nl.rijksoverheid.moz.fbs.common

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * Zet de `API-Version` response-header op basis van het request-pad, conform de
 * NL API Design Rules: de header communiceert de *API major version* (`v1`, `v2`)
 * en niet de applicatie-/build-versie. Een request naar `/api/v1/...` levert dus
 * `API-Version: v1`.
 *
 * Valt terug op `v1` wanneer het pad geen versiesegment bevat (bijv. openapi-spec,
 * health-endpoints, root).
 */
@Provider
class ApiVersionFilter : ContainerResponseFilter {

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        val path = requestContext.uriInfo.path
        val version = VERSION_PATTERN.find(path)?.value?.trimEnd('/') ?: DEFAULT_VERSION
        responseContext.headers.putSingle("API-Version", version)
    }

    private companion object {
        val VERSION_PATTERN = Regex("^v(\\d+)/")
        const val DEFAULT_VERSION = "v1"
    }
}
