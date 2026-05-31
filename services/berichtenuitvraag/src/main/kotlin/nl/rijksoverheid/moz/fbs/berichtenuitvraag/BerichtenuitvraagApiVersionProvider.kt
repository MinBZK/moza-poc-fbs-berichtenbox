package nl.rijksoverheid.moz.fbs.berichtenuitvraag

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.ApiVersionProvider

/**
 * Levert de `API-Version`-waarde aan [nl.rijksoverheid.moz.fbs.common.ApiVersionFilter]
 * (nu nog de majorversie; volledige-semver-harmonisatie via #570). De waarde komt uit
 * `ApiInfo.API_VERSION`, die op build-time uit `berichtenuitvraag-api.yaml` wordt
 * gegenereerd — zo blijft de OpenAPI-spec de enige bron van waarheid.
 */
@ApplicationScoped
class BerichtenuitvraagApiVersionProvider : ApiVersionProvider {
    override fun version(): String = ApiInfo.API_VERSION
}
