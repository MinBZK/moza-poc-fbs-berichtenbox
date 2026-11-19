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
        controleerOrigins("berichten-app.magazijnen", config.magazijnen().orElse(""), ontwikkel)

        require(OIN.matches(config.appManagerOin())) { "berichten-app.app-manager-oin (${config.appManagerOin()}) is geen OIN van 20 cijfers" }
    }

    companion object {
        private val OIN = Regex("\\d{20}")
        private val ORIGIN = Regex("^[a-z][a-z0-9+.-]*://[A-Za-z0-9.-]+(:[0-9]{1,5})?$")

        /**
         * Origins (`scheme://host[:port]`), gescheiden door spaties. Ze gaan letterlijk de CSP in:
         * een pad, `*` of `;` zou `connect-src` verruimen of een richtlijn toevoegen, en een regeleinde
         * of tab maakt de header ongeldig; zo'n teken haalt de controle per origin niet. Buiten
         * ontwikkel- en testmodus ten minste één: zonder blokkeert de browser elk magazijn, en dat
         * ziet de gebruiker als "niet bereikbaar".
         *
         * Een afgewezen origin staat met zijn plaats in de melding en niet met zijn waarde: die komt
         * in het bootlog, en een origin die de controle niet haalt, kan een wachtwoord bevatten.
         */
        fun controleerOrigins(naam: String, waarde: String, ontwikkel: Boolean) {
            val origins = waarde.split(' ').filter(String::isNotEmpty)

            require(ontwikkel || origins.isNotEmpty()) { "$naam is leeg; zonder magazijn-origins blokkeert de browser elk magazijn" }

            origins.forEachIndexed { index, origin ->
                require(ORIGIN.matches(origin)) { "$naam: origin ${index + 1} is geen origin (scheme://host[:port])" }
                require(ontwikkel || origin.startsWith("https://")) { "$naam: origin ${index + 1} ($origin) moet https zijn" }
            }
        }

        // Het adres komt letterlijk in de CSP-header: een `;`, `,`, spatie of quote in het pad zou daar
        // een eigen richtlijn of bron toevoegen.
        private val PAD = Regex("[A-Za-z0-9._~/-]*")

        private val SCHEMA = setOf("http", "https")

        /**
         * Absoluut, zonder gebruikersnaam, query of fragment, en eindigend op `/`: de app plakt er de
         * paden achter. Het pad bevat alleen tekens die in een CSP-bron niets betekenen. Buiten
         * ontwikkel- en testmodus https: de app stuurt er tokens heen of leest er het vertrouwen uit.
         */
        fun controleerAdres(naam: String, ruwAdres: String, ontwikkel: Boolean) {
            val uri = runCatching { URI.create(ruwAdres) }.getOrNull()
            // De melding komt in het bootlog; een wachtwoord in het adres hoort daar niet in. Daarom
            // alleen schema, host en pad, en niets van een adres zonder herkenbare host: daar kan een
            // wachtwoord met een `/` of `@` in het pad terechtgekomen zijn.
            val adres = uri?.takeIf { it.host != null }?.let { "${it.scheme}://${it.host}${it.rawPath.orEmpty()}" } ?: "(geen geldige URL)"
            val vorm = uri != null && uri.scheme in SCHEMA && uri.host != null && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && PAD.matches(uri.rawPath)

            require(vorm && ruwAdres.endsWith("/")) {
                "$naam ($adres) moet een absolute http(s)-URL zijn die op / eindigt, " +
                    "zonder gebruikersnaam, query of fragment, met een pad van alleen letters, cijfers en ._~/-"
            }

            require(ontwikkel || uri.scheme == "https") { "$naam ($adres) moet https zijn" }
        }
    }
}
