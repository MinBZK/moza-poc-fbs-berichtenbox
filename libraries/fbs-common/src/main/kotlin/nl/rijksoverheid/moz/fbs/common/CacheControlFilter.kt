package nl.rijksoverheid.moz.fbs.common

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * Zet `Cache-Control: no-store` op alle responses. Apart van SecurityHeadersFilter
 * zodat per-service of per-endpoint overrides mogelijk blijven.
 */
@Provider
class CacheControlFilter : ContainerResponseFilter {
    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        responseContext.headers.putSingle("Cache-Control", "no-store")
    }
}
