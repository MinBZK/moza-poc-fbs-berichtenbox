package nl.rijksoverheid.moz.fbs.appmanager

import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.kern.identificatie.Oin
import nl.rijksoverheid.moz.fbs.rechtstreeks.Verifier
import java.net.URI
import java.time.Clock
import java.time.Duration

/**
 * Toetst de configuratie bij de start, zodat een fout niet pas bij het eerste verzoek opvalt, of
 * erger: halverwege een activering, nadat de koppeling al verbruikt is. De looptijd van een
 * credential toetst [CredentialUitgifte], de sleutel zelf [Ondertekensleutel].
 */
@Startup
@ApplicationScoped
class ConfigControle(config: AppManagerConfig, klok: Clock) {

    init {
        val ontwikkel = LaunchMode.current().isDevOrTest

        controleerAdres("app-manager.terug-url", config.terugUrl(), ontwikkel)
        controleerAdres("app-manager.issuer-url", config.issuerUrl(), ontwikkel)

        // De app roept `<issuer-url>register_app` aan (4.4.1, stap 3) en de doorverwijzing gaat erheen.
        require(config.issuerUrl().endsWith("/")) { "app-manager.issuer-url (${config.issuerUrl()}) moet op / eindigen" }

        // Via de Oin-constructor: een App Manager zonder geldige OIN hoort de start te blokkeren.
        Oin(config.oin())

        require(config.kid().isNotBlank()) { "app-manager.kid mag niet leeg zijn" }

        // De standaard noemt geen grens; een registratie die langer leeft, geeft wie het token steelt
        // langer een bron van verse credentials, en houdt langer een plek in het quotum bezet.
        require(config.registratieGeldig() > Duration.ZERO && config.registratieGeldig() <= MAXIMALE_REGISTRATIE) {
            "app-manager.registratie-geldig (${config.registratieGeldig()}) moet positief zijn en hooguit $MAXIMALE_REGISTRATIE"
        }

        require(config.sleutelGeldigTot().isAfter(klok.instant().plus(Verifier.NONCE_GELDIG))) {
            "app-manager.sleutel-geldig-tot (${config.sleutelGeldigTot()}) ligt binnen de levensduur van een nonce; elke challenge zou geweigerd worden"
        }
    }

    companion object {
        private val MAXIMALE_REGISTRATIE: Duration = Duration.ofDays(30)

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
