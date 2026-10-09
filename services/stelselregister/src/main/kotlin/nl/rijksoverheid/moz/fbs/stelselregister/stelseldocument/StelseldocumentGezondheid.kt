package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Readiness

/**
 * Gereed betekent: er staat een onverlopen stelseldocument klaar. Zonder dat levert de dienst
 * alleen 503's, en hoort het platform er geen verkeer naartoe te sturen.
 *
 * De check noemt geen sleutel, certificaat of verloopdatum: het health-pad is publiek bereikbaar.
 */
@Readiness
@ApplicationScoped
class StelseldocumentGezondheid(
    private val uitgegeven: UitgegevenStelseldocument,
) : HealthCheck {

    override fun call(): HealthCheckResponse =
        HealthCheckResponse.named(NAAM).status(uitgegeven.geldend() != null).build()

    companion object {
        const val NAAM = "stelseldocument"
    }
}
