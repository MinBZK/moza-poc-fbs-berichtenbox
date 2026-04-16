package nl.rijksoverheid.moz.berichtensessiecache

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * Zet de API-Version header per service. Security-headers en cache-control komen
 * uit `libraries/fbs-common` (SecurityHeadersFilter, CacheControlFilter).
 */
@Provider
class ApiVersionFilter : ContainerResponseFilter {

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        val path = requestContext.uriInfo.path
        val version = VERSION_PATTERN.find(path)?.value?.trimEnd('/') ?: "v1"
        responseContext.headers.putSingle("API-Version", version)
    }

    companion object {
        private val VERSION_PATTERN = Regex("^v(\\d+)/")
    }
}
