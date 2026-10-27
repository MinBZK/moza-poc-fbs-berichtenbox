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
        val ontwikkel = LaunchMode.current().isDevOrTest

        controleerAdres("berichten-app.app-manager-url", config.appManagerUrl(), ontwikkel)
        controleerAdres("berichten-app.stelselregister-url", config.stelselregisterUrl(), ontwikkel)

        require(OIN.matches(config.appManagerOin())) { "berichten-app.app-manager-oin (${config.appManagerOin()}) is geen OIN van 20 cijfers" }
    }

    companion object {
        private val OIN = Regex("\\d{20}")

        /**
         * Absoluut, zonder query of fragment, en eindigend op `/`: de app plakt er de paden achter.
         * Buiten ontwikkel- en testmodus https: de app stuurt er tokens heen of leest er het
         * vertrouwen uit.
         */
        fun controleerAdres(naam: String, adres: String, ontwikkel: Boolean) {
            val uri = runCatching { URI.create(adres) }.getOrNull()
            val vorm = uri != null && uri.isAbsolute && uri.host != null && uri.rawQuery == null && uri.rawFragment == null

            require(vorm && adres.endsWith("/")) {
                "$naam ($adres) moet een absolute URL zijn die op / eindigt, zonder query of fragment"
            }

            require(ontwikkel || uri.scheme == "https") { "$naam ($adres) moet https zijn" }
        }
    }
}
