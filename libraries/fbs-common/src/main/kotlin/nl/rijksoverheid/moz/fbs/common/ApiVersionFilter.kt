package nl.rijksoverheid.moz.fbs.common

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Zet de `API-Version` response-header met de *API major version* (bv. `v1`), conform
 * de NL API Design Rules.
 *
 * Waarde komt uit de config-property `fbs.api.version`. Services genereren die waarde
 * uit hun OpenAPI-spec (zie de `ApiInfo.API_VERSION`-constante in de service-module)
 * en zetten 'm in hun `application.properties`, zodat de filter cross-service
 * herbruikbaar blijft zonder per request het URL-pad te parsen.
 *
 * Default `v1` zodat services die deze property niet (nog) zetten een zinnige waarde
 * terugsturen.
 */
@Provider
class ApiVersionFilter : ContainerResponseFilter {

    @ConfigProperty(name = "fbs.api.version", defaultValue = "v1")
    lateinit var apiVersion: String

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        responseContext.headers.putSingle("API-Version", apiVersion)
    }
}
