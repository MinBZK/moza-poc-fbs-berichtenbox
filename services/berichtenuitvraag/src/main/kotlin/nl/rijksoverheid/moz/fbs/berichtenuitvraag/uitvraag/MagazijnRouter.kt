package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.RestClientBuilder
import org.jboss.logging.Logger
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Bouwt en cachet [MagazijnClient]-instances per `magazijnId`. De sessiecache
 * levert bij elk bericht het bron-magazijn als `magazijnId`; deze router
 * vertaalt dat naar de URL uit [MagazijnenConfig] en levert een REST-client.
 *
 * Onbekende `magazijnId` → 502 Bad Gateway: de uitvraag-service "weet" niet
 * meer welke magazijnen er zijn dan zijn config zegt, dus is een mismatch
 * tussen sessiecache-data en uitvraag-config een infrastructuur-probleem dat
 * naar buiten geen 500 verdient (we kennen het probleem, maar de upstream-
 * topologie klopt niet).
 *
 * De `magazijnId`-waarden moeten exact overeenkomen met de magazijn-instances in
 * de sessiecache-config. De config wordt bij opstart gevalideerd
 * ([valideerConfigBijOpstart]) zodat een lege of kromme `magazijnen.urls` de
 * service laat falen bij boot i.p.v. bij het eerste verkeer.
 */
@ApplicationScoped
class MagazijnRouter(private val config: MagazijnenConfig) {

    private val clients = ConcurrentHashMap<String, MagazijnClient>()

    /**
     * Fail-fast op misconfiguratie: lege map of een waarde die geen geldige
     * http(s)-URI is, hoort de boot te blokkeren — niet pas een runtime-502/500
     * bij de eerste request te veroorzaken. (Bean Validation op de
     * `@ConfigMapping`-interface werkt hier niet betrouwbaar; een expliciete
     * startup-check wel.)
     */
    fun valideerConfigBijOpstart(@Observes startup: StartupEvent) {
        val urls = config.urls()

        require(urls.isNotEmpty()) { "magazijnen.urls is leeg; configureer minstens één magazijn-URL" }

        urls.forEach { (id, url) ->
            val uri = runCatching { URI.create(url) }
                .getOrElse { throw IllegalStateException("magazijnen.urls.$id is geen geldige URI: '$url'", it) }

            require(uri.scheme == "http" || uri.scheme == "https") {
                "magazijnen.urls.$id moet een http(s)-URL zijn, was: '$url'"
            }
        }
    }

    fun forMagazijn(magazijnId: String): MagazijnClient =
        clients.computeIfAbsent(magazijnId) { id ->
            val urls = config.urls()
            val url = urls[id]

            if (url == null) {
                log.errorf("Magazijn-routering: onbekend magazijnId=%s, bekende ids=%s", id, urls.keys)

                throw WebApplicationException(
                    "onbekende magazijnId '$id'; controleer magazijnen.urls in uitvraag-config",
                    Response.Status.BAD_GATEWAY,
                )
            }

            RestClientBuilder.newBuilder()
                .baseUri(URI.create(url))
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build(MagazijnClient::class.java)
        }

    private companion object {
        private val log: Logger = Logger.getLogger(MagazijnRouter::class.java)

        // Begrensde timeouts zodat een hangende magazijn-connectie een uitvraag-
        // request niet onbeperkt blokkeert; de ProcessingException die volgt op een
        // timeout mapt via mapUpstreamFout naar 502.
        private const val CONNECT_TIMEOUT_SECONDS = 2L
        private const val READ_TIMEOUT_SECONDS = 10L
    }
}
