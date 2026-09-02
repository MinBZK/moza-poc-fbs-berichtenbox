package nl.rijksoverheid.moz.fbs.magazijnsimulator

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * Zet de `API-Version` response-header met de volledige spec-versie, zoals de spec hem op elke
 * response declareert en de NL API Design Rules hem voorschrijven. Een client die de simulator
 * niet van een echt magazijn hoort te kunnen onderscheiden, ziet hier dezelfde waarde: beide
 * lezen hem uit `ApiInfo.SPEC_VERSION`, gegenereerd uit dezelfde spec.
 */
@Provider
class ApiVersionFilter : ContainerResponseFilter {

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        responseContext.headers.putSingle("API-Version", ApiInfo.SPEC_VERSION)
    }
}
