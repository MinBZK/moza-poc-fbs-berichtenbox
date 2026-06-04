package nl.rijksoverheid.moz.fbs.common

import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * Zet de `API-Version` response-header, conform de NL API Design Rules
 * (`core/version-header`). De ADR vraagt de volledige versie (`major.minor.patch`);
 * deze services emitten nu nog de majorversie (bv. `v1`) — harmonisatie van de
 * headerwaarde naar volledige semver loopt via #570.
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
