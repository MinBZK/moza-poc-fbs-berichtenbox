package nl.rijksoverheid.moz.fbs.common

import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * Zet de `API-Version` response-header met de *API major version* (bv. `v1`), conform
 * de NL API Design Rules.
 *
 * De waarde wordt geleverd door een service-specifieke [ApiVersionProvider]-bean, die
 * is afgeleid uit de gegenereerde `ApiInfo.API_VERSION`-constante (bron: OpenAPI-spec).
 * Zo blijft de filter cross-service herbruikbaar zonder dat de versie dubbel
 * onderhouden hoeft te worden in `application.properties`.
 */
@Provider
class ApiVersionFilter @Inject constructor(
    private val apiVersionProvider: ApiVersionProvider,
) : ContainerResponseFilter {

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        responseContext.headers.putSingle("API-Version", apiVersionProvider.version())
    }
}
