package nl.rijksoverheid.moz.fbs.common

/**
 * Levert de waarde die [ApiVersionFilter] in de `API-Version` response-header zet —
 * nu nog de majorversie (bv. `v1`); harmonisatie naar volledige semver via #570. Elke
 * service biedt een `@ApplicationScoped`-implementatie die de gegenereerde
 * `ApiInfo.API_VERSION`-constante teruggeeft, zodat de OpenAPI-spec de enige bron van
 * waarheid blijft.
 */
interface ApiVersionProvider {
    fun version(): String
}
