package nl.rijksoverheid.moz.fbs.beheerconsole

import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.common.ApiVersionProvider

/**
 * Levert de API-versie aan [nl.rijksoverheid.moz.fbs.common.ApiVersionFilter].
 *
 * TODO(#1483): uit `ApiInfo.SPEC_VERSION` halen zodra de console een eigen OpenAPI-spec heeft,
 * zoals de andere diensten; tot dan heeft hij geen endpoint om een versie op te zetten.
 */
@ApplicationScoped
class BeheerconsoleApiVersionProvider : ApiVersionProvider {
    override fun version(): String = "0.1.0"
}
