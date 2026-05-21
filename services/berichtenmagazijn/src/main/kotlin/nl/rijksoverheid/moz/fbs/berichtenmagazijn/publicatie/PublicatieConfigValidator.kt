package nl.rijksoverheid.moz.fbs.berichtenmagazijn.publicatie

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.time.Duration

/**
 * Valideert bij applicatie-start de cross-veld- en positief-invarianten op de
 * [PublicatieConfig]-`Duration`-waarden die Bean Validation declaratief niet kan
 * uitdrukken. Faalt fail-fast bij boot i.p.v. pas bij de eerste retry/pollronde:
 *
 *  - `polling.interval` en `opschonen.interval` moeten positief zijn.
 *  - per downstream: `backoff.basis` positief en `backoff.plafond >= basis`
 *    (een negatieve/zero basis of `plafond < basis` zou een `volgendePoging` in
 *    het verleden of een hot-retry-loop geven).
 */
@ApplicationScoped
class PublicatieConfigValidator(
    private val config: PublicatieConfig,
) {

    fun valideer(@Observes startup: StartupEvent) {
        vereisPositief("magazijn.publicatie.polling.interval", config.polling().interval())
        vereisPositief("magazijn.publicatie.opschonen.interval", config.opschonen().interval())

        config.downstreams().forEach { (key, downstream) ->
            val basis = downstream.backoff().basis()
            val plafond = downstream.backoff().plafond()
            vereisPositief("magazijn.publicatie.downstreams.$key.backoff.basis", basis)
            check(plafond >= basis) {
                "magazijn.publicatie.downstreams.$key.backoff.plafond ($plafond) " +
                    "mag niet kleiner zijn dan basis ($basis)"
            }
        }
    }

    private fun vereisPositief(property: String, waarde: Duration) {
        check(!waarde.isNegative && !waarde.isZero) {
            "$property moet positief zijn (kreeg $waarde)"
        }
    }
}
