package nl.rijksoverheid.moz.fbs.common

/**
 * Levert de API-majorversie (bv. `v1`) die [ApiVersionFilter] in de `API-Version`
 * response-header zet. Elke service biedt een `@ApplicationScoped`-implementatie die
 * de gegenereerde `ApiInfo.API_VERSION`-constante teruggeeft, zodat de OpenAPI-spec
 * de enige bron van waarheid blijft.
 */
interface ApiVersionProvider {
    fun version(): String
}
