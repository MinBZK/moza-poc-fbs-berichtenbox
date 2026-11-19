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

        // Het adres komt letterlijk in de CSP-header: een `;`, `,`, spatie of quote in het pad zou daar
        // een eigen richtlijn of bron toevoegen.
        private val PAD = Regex("[A-Za-z0-9._~/-]*")

        private val SCHEMA = setOf("http", "https")

        /**
         * Absoluut, zonder gebruikersnaam, query of fragment, en eindigend op `/`: de app plakt er de
         * paden van de standaard achter. Het pad bevat alleen tekens die in een CSP-bron niets
         * betekenen. Buiten ontwikkel- en testmodus https, want de app stuurt er het `session_token` heen.
         */
        fun controleerAppManagerUrl(ruwAdres: String, ontwikkel: Boolean) {
            val uri = runCatching { URI.create(ruwAdres) }.getOrNull()
            // De melding komt in het bootlog; een wachtwoord in het adres hoort daar niet in. Daarom
            // alleen schema, host en pad, en niets van een adres zonder herkenbare host: daar kan een
            // wachtwoord met een `/` of `@` in het pad terechtgekomen zijn.
            val adres = uri?.takeIf { it.host != null }?.let { "${it.scheme}://${it.host}${it.rawPath.orEmpty()}" } ?: "(geen geldige URL)"
            val vorm = uri != null && uri.scheme in SCHEMA && uri.host != null && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && PAD.matches(uri.rawPath)

            require(vorm && ruwAdres.endsWith("/")) {
                "berichten-app.app-manager-url ($adres) moet een absolute http(s)-URL zijn die op / eindigt, " +
                    "zonder gebruikersnaam, query of fragment, met een pad van alleen letters, cijfers en ._~/-"
            }

            require(ontwikkel || uri.scheme == "https") { "berichten-app.app-manager-url ($adres) moet https zijn" }
        }
    }
}
