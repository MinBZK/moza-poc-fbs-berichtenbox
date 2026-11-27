package nl.rijksoverheid.moz.fbs.beheerconsole.stelsel

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.common.OutboundTlsValidator
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Controles bij de start, zodat een verkeerde instelling de boot laat falen en niet pas het eerste
 * scherm:
 * - het stelselregister buiten dev en test alleen over https, zoals elk uitgaand adres in de keten;
 * - buiten dev en test een vastgelegde root. Zonder root controleert de console niets en toont hij
 *   elke lijst die het stelselregister-adres teruggeeft; dat mag alleen bewust, met
 *   [ZONDER_ROOT_ALERT_TOKEN] in de log bij elke start.
 */
@ApplicationScoped
class StelselregisterAdres(
    @param:ConfigProperty(name = "quarkus.profile") private val profiel: String,
    @param:ConfigProperty(name = "quarkus.rest-client.stelselregister.url") private val url: String,
    private val config: StelseldocumentConfig,
) {

    private val log = Logger.getLogger(StelselregisterAdres::class.java)

    fun controleer(@Observes start: StartupEvent) {
        OutboundTlsValidator.requireHttps(profiel, url, "quarkus.rest-client.stelselregister.url")

        if (config.root().isPresent || profiel in PROFIELEN_ZONDER_ROOT_EIS) return

        require(config.unsafeZonderRoot()) {
            "STELSELDOCUMENT_ROOT_PEM ontbreekt in profiel '$profiel': zonder root is het stelseldocument niet te " +
                "verifiëren. Zet hem, of zet FBS_BEHEERCONSOLE_UNSAFE_ZONDER_ROOT=true als dat bewust is."
        }

        log.warnf("%s: geen root vastgelegd in profiel '%s'; het stelseldocument wordt getoond zonder verificatie.", ZONDER_ROOT_ALERT_TOKEN, profiel)
    }

    companion object {
        /** Greppable marker; ops hangt hier een alert-regel aan, dus de waarde wijzigt niet zonder die mee te verhuizen. */
        const val ZONDER_ROOT_ALERT_TOKEN = "STELSELDOCUMENT_NIET_GETOETST"

        private val PROFIELEN_ZONDER_ROOT_EIS = setOf("dev", "test")
    }
}
