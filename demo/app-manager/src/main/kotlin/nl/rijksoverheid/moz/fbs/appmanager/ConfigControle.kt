package nl.rijksoverheid.moz.fbs.appmanager

import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.rechtstreeks.Verifier
import java.net.URI
import java.time.Clock

/**
 * Toetst de configuratie bij de start, zodat een fout niet pas bij het eerste verzoek opvalt, of
 * erger: halverwege een activering, nadat de koppeling al verbruikt is.
 */
@Startup
@ApplicationScoped
class ConfigControle(config: AppManagerConfig, klok: Clock) {

    init {
        val ontwikkel = LaunchMode.current().isDevOrTest

        controleerAdres("app-manager.terug-url", config.terugUrl(), ontwikkel)
        controleerAdres("app-manager.issuer-url", config.issuerUrl(), ontwikkel)

        require(config.sleutelGeldigTot().isAfter(klok.instant().plus(Verifier.NONCE_GELDIG))) {
            "app-manager.sleutel-geldig-tot (${config.sleutelGeldigTot()}) ligt binnen de levensduur van een nonce; elke challenge zou geweigerd worden"
        }
    }

    companion object {

        /** Absoluut, zonder fragment, en https buiten ontwikkel- en testmodus. */
        fun controleerAdres(naam: String, adres: String, ontwikkel: Boolean) {
            val uri = runCatching { URI.create(adres) }.getOrNull()

            require(uri != null && uri.isAbsolute && uri.host != null && uri.rawFragment == null) {
                "$naam ($adres) moet een absolute URL zonder fragment zijn"
            }

            require(ontwikkel || uri.scheme == "https") { "$naam ($adres) moet https zijn" }
        }
    }
}
