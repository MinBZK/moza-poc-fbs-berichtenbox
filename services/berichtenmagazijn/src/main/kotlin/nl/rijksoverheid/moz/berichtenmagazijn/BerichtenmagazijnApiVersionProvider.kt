package nl.rijksoverheid.moz.berichtenmagazijn

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.ApiVersionProvider

/**
 * Levert de API-majorversie aan [nl.rijksoverheid.moz.fbs.common.ApiVersionFilter].
 * De waarde komt uit `ApiInfo.API_VERSION`, die op build-time uit
 * `berichtenmagazijn-api.yaml` wordt gegenereerd — zo blijft de OpenAPI-spec de
 * enige bron van waarheid.
 */
@ApplicationScoped
class BerichtenmagazijnApiVersionProvider : ApiVersionProvider {
    override fun version(): String = ApiInfo.API_VERSION
}
