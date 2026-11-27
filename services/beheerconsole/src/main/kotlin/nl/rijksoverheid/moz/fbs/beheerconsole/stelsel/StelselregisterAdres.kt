package nl.rijksoverheid.moz.fbs.beheerconsole.stelsel

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.common.OutboundTlsValidator
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Het stelselregister buiten dev en test alleen over https, zoals elk uitgaand adres in de keten.
 * Het document is publiek, maar de verificatie leunt op wat er onderweg niet veranderd is.
 * Bij de start, zodat een verkeerd adres een boot laat falen en niet pas het eerste scherm.
 */
@ApplicationScoped
class StelselregisterAdres(
    @param:ConfigProperty(name = "quarkus.profile") private val profiel: String,
    @param:ConfigProperty(name = "quarkus.rest-client.stelselregister.url") private val url: String,
) {

    fun controleer(@Observes start: StartupEvent) {
        OutboundTlsValidator.requireHttps(profiel, url, "quarkus.rest-client.stelselregister.url")
    }
}
