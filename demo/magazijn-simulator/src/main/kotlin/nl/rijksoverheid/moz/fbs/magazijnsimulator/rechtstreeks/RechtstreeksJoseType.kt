package nl.rijksoverheid.moz.fbs.magazijnsimulator.rechtstreeks

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.ext.Provider

/**
 * Een geslaagd antwoord van de tweede ingang is altijd `application/jose`. De gegenereerde interface
 * noemt ook `application/problem+json` in `@Produces`, voor de foutantwoorden; zonder dit filter
 * krijgt een app die alleen dat accepteert een JWS onder het mediatype van een probleem.
 */
@Provider
class RechtstreeksJoseType : ContainerResponseFilter {

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        if (!requestContext.uriInfo.path.startsWith("/rechtstreeks/")) return

        if (responseContext.status !in SUCCES) return

        responseContext.headers.putSingle(HttpHeaders.CONTENT_TYPE, JOSE)
    }

    private companion object {
        const val JOSE = "application/jose"
        val SUCCES = 200..299
    }
}
