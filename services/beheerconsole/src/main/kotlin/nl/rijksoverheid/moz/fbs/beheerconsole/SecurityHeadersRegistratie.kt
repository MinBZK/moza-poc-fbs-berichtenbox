package nl.rijksoverheid.moz.fbs.beheerconsole

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
 * tegenhanger in het stelselregister; dat geldt hier net zo. Het verschil met die kopie is de
 * CSP: deze dienst serveert naast zijn API een webapp die zijn eigen script en stylesheet laadt,
 * dus elk pad buiten [API_PADEN] en `/q` krijgt de webapp-policy uit
 * [SecurityHeaders.voorWebapp].
 */
@ApplicationScoped
class SecurityHeadersRegistratie(
    @param:ConfigProperty(name = "quarkus.http.root-path", defaultValue = STANDAARD_ROOT_PAD)
    rootPad: String,
    @param:ConfigProperty(name = "quarkus.http.non-application-root-path", defaultValue = STANDAARD_BEHEERPAD)
    nonApplicationRootPad: String,
) {

    private val beheerpadRoot = SecurityHeaders.beheerpadRoot(rootPad, nonApplicationRootPad)

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

            SecurityHeaders.voorWebapp(context.normalizedPath(), beheerpadRoot, API_PADEN)
                .forEach { (naam, waarde) -> headers.set(naam, waarde) }

            if (headers.get(SecurityHeaders.CACHE_CONTROL) == null) {
                headers.set(SecurityHeaders.CACHE_CONTROL, SecurityHeaders.CACHE_CONTROL_DEFAULT)
            }
        }
    }

    private companion object {

        /** Zie de tegenhanger in het stelselregister: moet vóór elk filter dat kan afsluiten. */
        private const val PRIORITEIT = 10_000

        /** De paden met de strenge API-policy; de rest is app. */
        private val API_PADEN = listOf("/api", "/openapi.json")
    }
}

/** Compile-time-constanten voor de annotatie-argumenten; de waarden zijn die van Quarkus zelf. */
private const val STANDAARD_ROOT_PAD = "/"
private const val STANDAARD_BEHEERPAD = "q"
