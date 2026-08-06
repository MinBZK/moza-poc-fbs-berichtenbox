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

    /** `sslmode`-waarden die versleuteling garanderen; `prefer` valt stil terug op plaintext. */
    private val SSLMODES_MET_GARANTIE = setOf("require", "verify-ca", "verify-full")

    /**
     * Verifieert dat [url] een JDBC-verbinding opzet die daadwerkelijk versleutelt.
     * De JDBC-vorm heeft geen scheme om op te controleren, dus de check kijkt naar
     * `ssl=true` of een `sslmode` uit [SSLMODES_MET_GARANTIE].
     *
     * Verder identiek aan [requireHttps]: zelfde profielvrijstelling, zelfde
     * bewust-onveilige override met dezelfde alert-token-waarschuwing.
     *
     * @throws IllegalArgumentException als het profiel TLS vereist, de URL geen
     *   versleuteling garandeert, en de onveilige override niet expliciet aan staat.
     */
    fun requireJdbcTls(
        profile: String,
        url: String,
        configKey: String,
        unsafeAllowPlaintext: Boolean = false,
    ) {
        if (profile in PROFIELEN_ZONDER_TLS_EIS) return

        // pgJDBC zet de querystring sequentieel in een Properties-object: bij een dubbele
        // sleutel wint het laatste voorkomen. `associate` volgt dezelfde last-wins-regel
        // (een latere entry overschrijft een eerdere bij hetzelfde key), dus de guard
        // beoordeelt precies de waarde waarmee de driver ook daadwerkelijk verbindt —
        // anders zou `sslmode=require&sslmode=disable` hier ten onrechte als veilig gelden.
        val effectieveParameters = url.substringAfter('?', "")
            .split('&')
            .associate { parameter ->
                parameter.substringBefore('=').lowercase() to parameter.substringAfter('=', "")
            }

        // SslMode.of(Properties) in pgJDBC laat sslmode exclusief de modus bepalen zodra
        // de property gezet is; ssl wordt alleen geraadpleegd als sslmode ontbreekt. Een
        // aanwezige maar lege of onbekende sslmode-waarde valt dus niet terug op ssl —
        // fail-closed, want de driver kiest in dat geval ook niet stilzwijgend voor ssl.
        val sslmode = effectieveParameters["sslmode"]

        val isVersleuteld = if (sslmode != null) {
            sslmode.lowercase() in SSLMODES_MET_GARANTIE
        } else {
            effectieveParameters["ssl"].equals("true", ignoreCase = true)
        }

        if (unsafeAllowPlaintext && !isVersleuteld) {
            log.warning(
                "$TLS_DISABLED_ALERT_TOKEN: TLS-eis BEWUST uitgeschakeld voor $configKey in profiel " +
                    "'$profile' — persoonsgegevens (o.a. BSN) gaan PLAINTEXT over het netwerk. Alleen " +
                    "toegestaan bij mesh-mTLS of zonder echte persoonsgegevens.",
            )
        }

        require(isVersleuteld || unsafeAllowPlaintext) {
            "$configKey MOET een versleutelde verbinding opzetten in profiel '$profile' — " +
                "gebruik ssl=true of sslmode=require/verify-ca/verify-full " +
                "(BIO 13.2.1: persoonsgegevens versleuteld over netwerk)."
        }
    }
}
