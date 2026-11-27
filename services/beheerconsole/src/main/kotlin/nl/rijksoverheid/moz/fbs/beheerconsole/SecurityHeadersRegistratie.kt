package nl.rijksoverheid.moz.fbs.beheerconsole

import io.quarkus.runtime.LaunchMode
import io.quarkus.vertx.http.runtime.filters.Filters
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import nl.rijksoverheid.moz.fbs.common.SecurityHeaders
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Plaatst de [SecurityHeaders] op élke response: de API, `/q` en de schermen van de app.
 *
 * Waarom op de Vert.x-laag, en waarom per dienst en niet in fbs-common, staat bij de
 * tegenhanger in het stelselregister; dat geldt hier net zo. Het verschil met die kopie: elk pad
 * buiten [API_PADEN] en `/q` is de webapp en krijgt de policy uit [SecurityHeaders.voorWebapp], en
 * het app-document krijgt `no-cache` in plaats van de dagcache van Quinoa.
 *
 * De paden worden gelezen ná de omleiding van Quinoa: een app-pad zonder bestand is dan al `/`.
 * Een API-pad dat Quinoa niet negeert (`quarkus.quinoa.ignored-path-prefixes`), valt daardoor in
 * de app-tak; houd die lijst en [API_PADEN] dus gelijk.
 */
@ApplicationScoped
class SecurityHeadersRegistratie(
    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = STANDAARD_ROOT_PAD)
    rootPad: String,
    @ConfigProperty(name = "quarkus.http.non-application-root-path", defaultValue = STANDAARD_BEHEERPAD)
    nonApplicationRootPad: String,
) {

    private val beheerpadRoot = SecurityHeaders.beheerpadRoot(rootPad, nonApplicationRootPad)

    private val root = rootPad.removeSuffix("/")

    private val apiPaden = API_PADEN.map { root + it }

    private val assets = root + ASSETS

    private val ontwikkelserver = LaunchMode.current() == LaunchMode.DEVELOPMENT

    fun registreer(@Observes filters: Filters) {
        filters.register(
            { context ->
                plaats(context)
                context.next()
            },
            PRIORITEIT,
        )
    }

    /**
     * De headers gaan er pas op vlak vóór ze de deur uit gaan: alles wat daarna nog schrijft,
     * zou er anders zijn eigen waarde naast of overheen zetten.
     */
    private fun plaats(context: RoutingContext) {
        context.addHeadersEndHandler {
            val headers = context.response().headers()

            val pad = context.normalizedPath()
            val voorPad = SecurityHeaders.voorWebapp(pad, beheerpadRoot, apiPaden, ontwikkelserver)

            voorPad.forEach { (naam, waarde) -> headers.set(naam, waarde) }

            // Quinoa serveert index.html als `public, immutable, max-age=86400`. Dan houdt een
            // browser na een uitrol een dag de oude app vast, en die vraagt assets op die er niet
            // meer zijn. Het document moet elke keer gevalideerd worden; de assets onder /assets/
            // dragen een hash in hun naam en mogen blijven zoals Quinoa ze zet.
            if (isAppDocument(pad, voorPad)) {
                headers.set(SecurityHeaders.CACHE_CONTROL, APP_DOCUMENT_CACHE)
            }

            if (headers.get(SecurityHeaders.CACHE_CONTROL) == null) {
                headers.set(SecurityHeaders.CACHE_CONTROL, SecurityHeaders.CACHE_CONTROL_DEFAULT)
            }
        }
    }

    /** Een app-pad buiten /assets/: daar staat index.html, onder welk scherm-pad dan ook. */
    private fun isAppDocument(pad: String, voorPad: Map<String, String>): Boolean =
        !pad.startsWith(assets) && voorPad != SecurityHeaders.voorPad(pad, beheerpadRoot)

    private companion object {

        /** Zie de tegenhanger in het stelselregister: moet vóór elk filter dat kan afsluiten. */
        private const val PRIORITEIT = 10_000

        /** De paden met de strenge API-policy, relatief aan het root-pad; de rest is app. */
        private val API_PADEN = listOf("/api", "/openapi.json", "/.well-known")

        /** Waar Vite de gebouwde bestanden zet, met een hash in de naam. */
        private const val ASSETS = "/assets/"

        private const val APP_DOCUMENT_CACHE = "no-cache"
    }
}

/** Compile-time-constanten voor de annotatie-argumenten; de waarden zijn die van Quarkus zelf. */
private const val STANDAARD_ROOT_PAD = "/"
private const val STANDAARD_BEHEERPAD = "q"
