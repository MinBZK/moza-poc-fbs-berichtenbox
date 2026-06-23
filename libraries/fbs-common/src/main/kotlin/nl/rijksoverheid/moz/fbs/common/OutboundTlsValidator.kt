package nl.rijksoverheid.moz.fbs.common

import java.util.logging.Logger

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

    private val log = Logger.getLogger(OutboundTlsValidator::class.java.name)

    /**
     * Stabiel, greppable token vooraan de plaintext-waarschuwing. Ops koppelt hier een
     * alert-regel aan; de waarde mag nooit wijzigen zonder de bijbehorende alert mee te
     * verhuizen (anders valt de detectie stil).
     */
    const val TLS_DISABLED_ALERT_TOKEN = "OUTBOUND_TLS_DISABLED"

    private val PROFIELEN_ZONDER_TLS_EIS = setOf("dev", "test")

    /**
     * Verifieert dat [endpoint] met `https://` begint in non-dev/test-profielen.
     * [configKey] verschijnt in de foutmelding zodat ops direct weet welke
     * property aangepast moet worden.
     *
     * [unsafeAllowPlaintext] zet de TLS-eis voor dit endpoint BEWUST ONVEILIG uit:
     * persoonsgegevens (o.a. de BSN in het LDV-`dataSubjectId`) gaan dan plaintext over
     * het netwerk. Alleen verantwoord wanneer het netwerk zelf transport-security levert
     * (mesh-mTLS) óf wanneer er geen echte persoonsgegevens stromen (test-data). Bij gebruik
     * wordt bij elke boot een WARNING gelogd met het stabiele token [TLS_DISABLED_ALERT_TOKEN];
     * ops MOET hierop een alert-regel (Loki/SIEM) configureren — zonder die regel scrolt de
     * waarschuwing weg en blijft een onbedoeld onversleuteld endpoint onopgemerkt.
     * Default false (fail-closed) zodat het nooit per ongeluk aan staat.
     *
     * @throws IllegalArgumentException als het profiel TLS vereist, het endpoint geen
     *   `https://` is, en de onveilige override niet expliciet aan staat.
     */
    fun requireHttps(
        profile: String,
        endpoint: String,
        configKey: String,
        unsafeAllowPlaintext: Boolean = false,
    ) {
        if (profile in PROFIELEN_ZONDER_TLS_EIS) return

        val isHttps = endpoint.startsWith("https://")

        if (unsafeAllowPlaintext && !isHttps) {
            log.warning(
                "$TLS_DISABLED_ALERT_TOKEN: TLS-eis BEWUST uitgeschakeld voor $configKey ('$endpoint') in " +
                    "profiel '$profile' — persoonsgegevens (o.a. BSN) gaan PLAINTEXT over het netwerk. Alleen " +
                    "toegestaan bij mesh-mTLS of zonder echte persoonsgegevens.",
            )
        }

        require(isHttps || unsafeAllowPlaintext) {
            "$configKey MOET https:// gebruiken in profiel '$profile' " +
                "(BIO 13.2.1: persoonsgegevens versleuteld over netwerk). Huidige waarde: '$endpoint'"
        }
    }
}
