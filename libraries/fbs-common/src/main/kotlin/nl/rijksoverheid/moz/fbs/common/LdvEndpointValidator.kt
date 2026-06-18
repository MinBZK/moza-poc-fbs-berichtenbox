package nl.rijksoverheid.moz.fbs.common

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Borgt dat het LDV (Logboek Dataverwerkingen) endpoint in productie-achtige
 * profielen TLS (https://) gebruikt. Persoonsgegevens (zoals dataSubjectId
 * met BSN) mogen niet onversleuteld over het netwerk — BIO 13.2.1 / AVG
 * art. 32. In `dev` en `test` mag http:// voor lokale containers.
 *
 * Delegeert naar [OutboundTlsValidator]; deze klasse bestaat alleen om de
 * config-keys vast te leggen en als `@ApplicationScoped`-bean een startup-
 * event te observeren.
 */
@ApplicationScoped
class LdvEndpointValidator(
    @param:ConfigProperty(name = "logboekdataverwerking.clickhouse.endpoint") private val endpoint: String,
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
    // BEWUST ONVEILIG: zet de https-eis op het LDV-endpoint uit. Alleen voor omgevingen
    // waar ClickHouse intern blijft én het netwerk transport-security levert (mesh-mTLS),
    // of waar geen echte persoonsgegevens stromen. Default false (fail-closed).
    @param:ConfigProperty(name = UNSAFE_PLAINTEXT_KEY, defaultValue = "false")
    private val unsafeAllowPlaintext: Boolean,
) {

    fun onStartup(@Observes event: StartupEvent) {
        validate(profile, endpoint, unsafeAllowPlaintext)
    }

    companion object {
        private const val CONFIG_KEY = "logboekdataverwerking.clickhouse.endpoint"
        const val UNSAFE_PLAINTEXT_KEY = "fbs.ldv.unsafe-allow-plaintext-endpoint"

        fun validate(profile: String, endpoint: String, unsafeAllowPlaintext: Boolean = false) {
            OutboundTlsValidator.requireHttps(profile, endpoint, CONFIG_KEY, unsafeAllowPlaintext)
        }
    }
}
