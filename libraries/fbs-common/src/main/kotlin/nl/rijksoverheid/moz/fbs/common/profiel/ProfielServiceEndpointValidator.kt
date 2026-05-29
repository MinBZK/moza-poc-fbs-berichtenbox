package nl.rijksoverheid.moz.fbs.common.profiel

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.common.OutboundTlsValidator
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Borgt dat het Profiel-Service-endpoint in productie-achtige profielen TLS
 * (https://) gebruikt. De client zet BSN/RSIN/KVK in het URL-pad (extern
 * contract); onversleuteld verkeer zou de waarde lekken naar netwerk en
 * intermediaire proxy-toegangslogs (BIO 13.2.1 / AVG art. 32). In `dev` en
 * `test` mag http:// voor lokale containers en WireMock.
 *
 * Delegeert naar [OutboundTlsValidator]; deze klasse bestaat alleen om de
 * config-keys vast te leggen en als `@ApplicationScoped`-bean een startup-
 * event te observeren.
 */
@ApplicationScoped
class ProfielServiceEndpointValidator(
    @param:ConfigProperty(name = "quarkus.rest-client.profiel-service.url") private val endpoint: String,
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
) {

    fun onStartup(@Observes event: StartupEvent) {
        validate(profile, endpoint)
    }

    companion object {
        private const val CONFIG_KEY = "quarkus.rest-client.profiel-service.url"

        fun validate(profile: String, endpoint: String) {
            OutboundTlsValidator.requireHttps(profile, endpoint, CONFIG_KEY)
        }
    }
}
