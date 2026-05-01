package nl.rijksoverheid.moz.fbs.berichtenmagazijn

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class LdvEndpointValidator(
    @param:ConfigProperty(name = "logboekdataverwerking.clickhouse.endpoint") private val endpoint: String,
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
                "logboekdataverwerking.clickhouse.endpoint MOET https:// gebruiken in profiel '$profile' " +
                    "(BIO 13.2.1: persoonsgegevens versleuteld over netwerk). Huidige waarde: '$endpoint'"
            }
        }
    }
}
