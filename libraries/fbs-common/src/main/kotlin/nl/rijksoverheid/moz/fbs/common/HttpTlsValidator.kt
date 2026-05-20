package nl.rijksoverheid.moz.fbs.common

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional

/**
 * Borgt dat een service met persoonsgegevens (BSN, RSIN) in productie-achtige
 * profielen geen plain-HTTP accepteert. TLS-terminatie mag via een API-gateway
 * of service-mesh (Istio/Linkerd) gebeuren; in dat geval moet ops dit expliciet
 * markeren met `fbs.http.tls.termination=mesh`. Default verwachten we
 * TLS in de Quarkus-laag zelf via een geconfigureerde keystore.
 *
 * BIO 13.2.1 / AVG art. 32: persoonsgegevens versleuteld over het netwerk.
 * In `dev` en `test` mag http:// voor lokale containers.
 */
@ApplicationScoped
class HttpTlsValidator(
    @param:ConfigProperty(name = "quarkus.profile") private val profile: String,
    @param:ConfigProperty(name = "quarkus.http.insecure-requests", defaultValue = "enabled")
    private val insecureRequests: String,
    @param:ConfigProperty(name = "quarkus.http.ssl.certificate.key-store-file")
    private val keyStoreFile: Optional<String>,
    @param:ConfigProperty(name = "fbs.http.tls.termination", defaultValue = "app")
    private val tlsTermination: String,
) {

    fun onStartup(@Observes event: StartupEvent) {
        validate(profile, insecureRequests, keyStoreFile.orElse(null), tlsTermination)
    }

    companion object {
        private val log = Logger.getLogger(HttpTlsValidator::class.java)
        private val PROFIELEN_ZONDER_TLS_EIS = setOf("dev", "test")
        private val GELDIGE_TLS_TERMINATIONS = setOf("app", "mesh")

        fun validate(
            profile: String,
            insecureRequests: String,
            keyStoreFile: String?,
            tlsTermination: String,
        ) {
            if (profile in PROFIELEN_ZONDER_TLS_EIS) {
                log.infof("HTTP TLS-validator overgeslagen voor profiel '%s' (dev/test mag http)", profile)
                return
            }
            require(tlsTermination in GELDIGE_TLS_TERMINATIONS) {
                "fbs.http.tls.termination moet 'app' of 'mesh' zijn, kreeg '$tlsTermination'"
            }
            if (tlsTermination == "mesh") {
                log.infof(
                    "HTTP TLS-validator: TLS-terminatie via service-mesh aangenomen voor profiel '%s' " +
                        "(fbs.http.tls.termination=mesh). Ops MOET in deze omgeving plain-HTTP afdwingen via " +
                        "mesh-policy.",
                    profile,
                )
                return
            }
            require(insecureRequests != "enabled") {
                "quarkus.http.insecure-requests='enabled' is niet toegestaan in profiel '$profile' " +
                    "(BIO 13.2.1: persoonsgegevens versleuteld over netwerk). Zet op 'disabled' of " +
                    "'redirect', of declareer mesh-terminatie via fbs.http.tls.termination=mesh."
            }
            require(!keyStoreFile.isNullOrBlank()) {
                "quarkus.http.ssl.certificate.key-store-file ontbreekt in profiel '$profile' " +
                    "(BIO 13.2.1). Configureer een TLS-keystore of declareer mesh-terminatie via " +
                    "fbs.http.tls.termination=mesh."
            }
            log.infof("HTTP TLS-validator: app-niveau TLS-config aanwezig voor profiel '%s'", profile)
        }
    }
}
