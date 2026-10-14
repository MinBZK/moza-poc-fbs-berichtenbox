package nl.rijksoverheid.moz.fbs.stelselregister

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.ApiVersionProvider

/**
 * Levert de volledige API-versie (semver) aan [nl.rijksoverheid.moz.fbs.common.ApiVersionFilter].
 * De waarde komt uit `ApiInfo.SPEC_VERSION`, die op build-time uit `stelselregister-api.yaml`
 * wordt gegenereerd — zo blijft de OpenAPI-spec de enige bron van waarheid.
 */
@ApplicationScoped
class StelselregisterApiVersionProvider : ApiVersionProvider {
    override fun version(): String = ApiInfo.SPEC_VERSION
}
