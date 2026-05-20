package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Borgt dat het Profiel-Service-endpoint in productie-achtige profielen TLS
 * (https://) gebruikt. De client zet BSN/RSIN/KVK in het URL-pad (extern
 * contract); onversleuteld verkeer zou de waarde lekken naar netwerk en
 * intermediaire proxy-toegangslogs (BIO 13.2.1 / AVG art. 32). In `dev` en
 * `test` mag http:// voor lokale containers en WireMock.
 *
 * Spiegelt [nl.rijksoverheid.moz.fbs.common.LdvEndpointValidator]; staat
 * service-lokaal omdat de configuratie-key specifiek is voor het magazijn.
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
        private val PROFIELEN_ZONDER_TLS_EIS = setOf("dev", "test")

        fun validate(profile: String, endpoint: String) {
            if (profile in PROFIELEN_ZONDER_TLS_EIS) return
            require(endpoint.startsWith("https://")) {
                "quarkus.rest-client.profiel-service.url MOET https:// gebruiken in profiel '$profile' " +
                    "(BIO 13.2.1: persoonsgegevens versleuteld over netwerk). Huidige waarde: '$endpoint'"
            }
        }
    }
}
