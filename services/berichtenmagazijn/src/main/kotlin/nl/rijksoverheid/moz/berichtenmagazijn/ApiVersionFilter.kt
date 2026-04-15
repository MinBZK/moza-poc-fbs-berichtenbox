package nl.rijksoverheid.moz.berichtenmagazijn

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Zet de API-Version header per service. Security-headers en cache-control komen
 * uit `libraries/fbs-common` (SecurityHeadersFilter, CacheControlFilter).
 */
@Provider
class ApiVersionFilter(
    @ConfigProperty(name = "quarkus.application.version") private val apiVersion: String,
) : ContainerResponseFilter {

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        responseContext.headers.putSingle("API-Version", apiVersion)
    }
}
