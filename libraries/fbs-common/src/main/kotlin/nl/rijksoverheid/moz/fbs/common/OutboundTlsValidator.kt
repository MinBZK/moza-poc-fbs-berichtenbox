package nl.rijksoverheid.moz.fbs.common

/**
 * Gedeelde TLS-check voor uitgaande URL-endpoints (REST-clients, ClickHouse-
 * connector, etc.). Borgt dat persoonsgegevens niet onversleuteld over het
 * netwerk gaan in productie-achtige profielen (BIO 13.2.1 / AVG art. 32).
 * In `dev` en `test` mag http:// voor lokale containers en stubs.
 *
 * Onderscheid met [HttpTlsValidator]: die controleert de **inkomende** HTTP-
 * server (key-store, `quarkus.http.insecure-requests`, mesh-terminatie). Deze
 * helper draait om **uitgaande** URLs waar de service zelf naartoe belt en
 * waarvan het scheme bekend is uit config.
 */
object OutboundTlsValidator {

    private val PROFIELEN_ZONDER_TLS_EIS = setOf("dev", "test")

    /**
     * Verifieert dat [endpoint] met `https://` begint in non-dev/test-profielen.
     * [configKey] verschijnt in de foutmelding zodat ops direct weet welke
     * property aangepast moet worden.
     *
     * @throws IllegalArgumentException als het profiel TLS vereist en het
     *   endpoint niet met `https://` begint.
     */
    fun requireHttps(profile: String, endpoint: String, configKey: String) {
        if (profile in PROFIELEN_ZONDER_TLS_EIS) return

        require(endpoint.startsWith("https://")) {
            "$configKey MOET https:// gebruiken in profiel '$profile' " +
                "(BIO 13.2.1: persoonsgegevens versleuteld over netwerk). Huidige waarde: '$endpoint'"
        }
    }
}
