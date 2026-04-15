package nl.rijksoverheid.moz.fbs.common

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * Zet `Cache-Control: no-store` als de resource zelf geen Cache-Control heeft gezet.
 * Scheiding van [SecurityHeadersFilter] zodat een endpoint dat bewust cacheable is
 * zijn eigen Cache-Control kan handhaven zonder in `no-store` verloren te gaan.
 */
@Provider
class CacheControlFilter : ContainerResponseFilter {
    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        if (!responseContext.headers.containsKey("Cache-Control")) {
            responseContext.headers.putSingle("Cache-Control", "no-store")
        }
    }
}
