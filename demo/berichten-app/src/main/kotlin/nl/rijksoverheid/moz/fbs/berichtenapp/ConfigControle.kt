package nl.rijksoverheid.moz.fbs.berichtenapp

import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI

/** Toetst de configuratie bij de start, zodat een fout niet pas in de browser van een gebruiker opvalt. */
@Startup
@ApplicationScoped
class ConfigControle(config: BerichtenAppConfig) {

    init {
        controleerAppManagerUrl(config.appManagerUrl(), LaunchMode.current().isDevOrTest)

        require(OIN.matches(config.appManagerOin())) { "berichten-app.app-manager-oin (${config.appManagerOin()}) is geen OIN van 20 cijfers" }
    }

    companion object {
        private val OIN = Regex("\\d{20}")

        /**
         * Absoluut, zonder query of fragment, en eindigend op `/`: de app plakt er de paden van de
         * standaard achter. Buiten ontwikkel- en testmodus https, want de app stuurt er het
         * `session_token` heen.
         */
        fun controleerAppManagerUrl(adres: String, ontwikkel: Boolean) {
            val uri = runCatching { URI.create(adres) }.getOrNull()
            val vorm = uri != null && uri.isAbsolute && uri.host != null && uri.rawQuery == null && uri.rawFragment == null

            require(vorm && adres.endsWith("/")) {
                "berichten-app.app-manager-url ($adres) moet een absolute URL zijn die op / eindigt, zonder query of fragment"
            }

            require(ontwikkel || uri.scheme == "https") { "berichten-app.app-manager-url ($adres) moet https zijn" }
        }
    }
}
