package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.RestClientBuilder
import org.jboss.logging.Logger
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

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
 * Bij een single-magazijn dev-setup hoort de sessiecache `magazijnId=default`
 * te gebruiken — de config heeft dan één entry `magazijnen.urls.default=...`.
 */
@ApplicationScoped
class MagazijnRouter(private val config: MagazijnenConfig) {

    private val clients = ConcurrentHashMap<String, MagazijnClient>()

    fun forMagazijn(magazijnId: String): MagazijnClient =
        clients.computeIfAbsent(magazijnId) { id ->
            val url = config.urls()[id]
                ?: throw WebApplicationException(
                    "onbekende magazijnId '$id'; controleer magazijnen.urls in uitvraag-config",
                    Response.Status.BAD_GATEWAY,
                ).also { log.errorf("Magazijn-routering: onbekend magazijnId=%s, bekende ids=%s", id, config.urls().keys) }

            RestClientBuilder.newBuilder()
                .baseUri(URI.create(url))
                .build(MagazijnClient::class.java)
        }

    private companion object {
        private val log: Logger = Logger.getLogger(MagazijnRouter::class.java)
    }
}
