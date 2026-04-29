package nl.rijksoverheid.moz.fbs.berichtensessiecache

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

@Provider
class SecurityHeadersFilter : ContainerResponseFilter {

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        responseContext.headers.putSingle("Strict-Transport-Security", "max-age=31536000")
        responseContext.headers.putSingle("X-Frame-Options", "DENY")
        responseContext.headers.putSingle("X-Content-Type-Options", "nosniff")
        responseContext.headers.putSingle("Content-Security-Policy", "frame-ancestors 'none'")
        responseContext.headers.putSingle("Cache-Control", "no-store")
    }
}
