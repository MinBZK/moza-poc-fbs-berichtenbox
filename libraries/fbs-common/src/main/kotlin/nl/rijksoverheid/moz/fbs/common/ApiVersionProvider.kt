package nl.rijksoverheid.moz.fbs.common

/**
 * Levert de volledige API-versie (semver, bv. `0.1.0`) die [ApiVersionFilter] in de `API-Version`
 * response-header zet. Elke service biedt een `@ApplicationScoped`-implementatie die
 * de gegenereerde `ApiInfo.SPEC_VERSION`-constante teruggeeft, zodat de OpenAPI-spec
 * de enige bron van waarheid blijft.
 */
interface ApiVersionProvider {
    fun version(): String
}
