package nl.rijksoverheid.moz.fbs.stelselregister.stelseldocument

import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI

/**
 * De twee `/.well-known`-paden. Ze staan op de Vert.x-router en niet in een JAX-RS-resource: alle
 * resources hangen onder `/api/v1`, en deze paden liggen door hun RFC's vast op de root van de
 * host. Ze horen daarmee niet bij de API en dragen geen `API-Version`.
 */
@ApplicationScoped
class WellKnownRoutes(
    private val sleutelbron: Sleutelbron,
    @ConfigProperty(name = "stelselregister.security-txt-url") securityTxtUrl: String,
) {

    /**
     * Bij het opbouwen gecontroleerd: een doel zonder https zou een melder van een kwetsbaarheid
     * naar een onversleuteld of onbedoeld adres sturen, en dat valt pas op als iemand het pad opvraagt.
     */
    private val securityTxtDoel: String = URI.create(securityTxtUrl.trim()).also {
        require(it.scheme == "https" && !it.host.isNullOrBlank()) {
            "stelselregister.security-txt-url moet een absolute https-URL zijn, was: '$securityTxtUrl'"
        }
    }.toString()

    private val sleutelset: Sleutelset by lazy { Sleutelset(sleutelbron.sleutel) }

    fun registreer(@Observes router: Router) {
        router.route(PAD_SLEUTELSET).method(HttpMethod.GET).method(HttpMethod.HEAD).handler(::sleutelset)
        router.route(PAD_SECURITY_TXT).method(HttpMethod.GET).method(HttpMethod.HEAD).handler(::securityTxt)
    }

    /**
     * De sleutelset is een gemak voor wie de sleutels wil inzien, geen bron van vertrouwen: hij
     * komt van dezelfde origin als het document. Een afnemer vertrouwt op de keten in `x5c`.
     */
    private fun sleutelset(context: RoutingContext) {
        val antwoord = context.response()
            .putHeader(HttpHeaders.ETAG, sleutelset.etag)
            .putHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_SLEUTELSET)

        // Zie StelseldocumentResource.VARY: zonder deze header kan een gedeelde cache een antwoord
        // zonder CORS-headers uitleveren aan een browser.
        if (!antwoord.headers().contains(HttpHeaders.VARY)) antwoord.putHeader(HttpHeaders.VARY, StelseldocumentResource.VARY)

        if (Etag.komtOvereen(context.request().getHeader(HttpHeaders.IF_NONE_MATCH), sleutelset.etag)) {
            antwoord.setStatusCode(NIET_GEWIJZIGD).end()
        } else {
            antwoord.putHeader(HttpHeaders.CONTENT_TYPE, MEDIATYPE_SLEUTELSET).end(sleutelset.json)
        }
    }

    /**
     * Doorverwijzen in plaats van een eigen bestand: contact, `Expires` en ondertekening blijven
     * zo op één beheerde plek. RFC 9116 §3 staat de redirect toe.
     */
    private fun securityTxt(context: RoutingContext) {
        context.response()
            .setStatusCode(GEVONDEN)
            .putHeader(HttpHeaders.LOCATION, securityTxtDoel)
            .putHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_SLEUTELSET)
            .end()
    }

    companion object {
        const val PAD_SLEUTELSET = "/.well-known/jwks.json"
        const val PAD_SECURITY_TXT = "/.well-known/security.txt"
        const val MEDIATYPE_SLEUTELSET = "application/jwk-set+json"

        /** Langer dan het document: de sleutelset verandert alleen bij een rotatie. */
        const val CACHE_CONTROL_SLEUTELSET = "public, max-age=3600"

        private const val GEVONDEN = 302
        private const val NIET_GEWIJZIGD = 304
    }
}
