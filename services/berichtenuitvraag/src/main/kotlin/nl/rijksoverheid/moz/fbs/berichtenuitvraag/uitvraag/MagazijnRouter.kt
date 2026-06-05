package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.common.identificatie.Oin
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijninschrijving
import nl.rijksoverheid.moz.fbs.magazijnregister.Magazijnregister
import org.eclipse.microprofile.rest.client.RestClientBuilder
import org.jboss.logging.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Bouwt en cachet [MagazijnClient]-instances per `magazijnId`. De sessiecache
 * levert bij elk bericht het bron-magazijn als `magazijnId` (= afzender-OIN);
 * deze router zoekt de bijbehorende inschrijving op in het [Magazijnregister]
 * en levert een REST-client naar dat endpoint.
 *
 * Onbekende of niet-OIN-vormige `magazijnId` → 502 Bad Gateway: de
 * uitvraag-service "weet" niet meer welke magazijnen er zijn dan het register
 * zegt, dus is een mismatch tussen sessiecache-data en register een
 * infrastructuur-probleem dat naar buiten geen 500 verdient (we kennen het
 * probleem, maar de upstream-topologie klopt niet).
 *
 * Fail-fast bij boot (leeg register, ongeldige OIN-key of URL) is belegd in
 * de register-library zelf; deze router hoeft alleen nog te routeren.
 */
@ApplicationScoped
class MagazijnRouter(
    private val register: Magazijnregister,
    private val config: MagazijnRouterConfig,
) {

    private val clients = ConcurrentHashMap<String, MagazijnClient>()

    fun forMagazijn(magazijnId: String): MagazijnClient =
        clients.computeIfAbsent(magazijnId) { id ->
            val inschrijving = inschrijvingVoor(id)

            // Builder-fouten (RestClientDefinitionException) zijn een config-/topologie-
            // probleem, geen bug in deze service. Zonder deze wrap ontsnapt de raw
            // exception langs mapUpstreamFout naar een 500 die on-call ten onrechte de
            // uitvraag-service in stuurt; map daarom expliciet → 502.
            runCatching {
                RestClientBuilder.newBuilder()
                    .baseUri(inschrijving.url)
                    .connectTimeout(config.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .readTimeout(config.readTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .build(MagazijnClient::class.java)
            }.getOrElse { e ->
                log.errorf(e, "Magazijn-routering: kon REST-client niet bouwen voor magazijnId=%s url=%s", id, inschrijving.url)

                throw upstreamBadGateway("magazijn-client-configuratie ongeldig voor '$id'")
            }
        }

    private fun inschrijvingVoor(id: String): Magazijninschrijving {
        // magazijnId == afzender-OIN (register-conventie). Specifiek
        // IllegalArgumentException: validatiefout uit de Oin-constructor; een Error
        // moet doorvloeien naar het JVM-vangnet.
        val oin = try {
            Oin(id)
        } catch (ex: IllegalArgumentException) {
            log.errorf(ex, "Magazijn-routering: magazijnId=%s is geen geldige OIN", id)

            throw WebApplicationException(
                "ongeldige magazijnId '$id'; magazijn-ids zijn afzender-OINs uit het magazijnregister",
                Response.Status.BAD_GATEWAY,
            )
        }

        return register.voorOin(oin) ?: run {
            log.errorf("Magazijn-routering: onbekend magazijnId=%s, bekende ids=%s", id, register.alle().map { it.oin.waarde })

            throw WebApplicationException(
                "onbekende magazijnId '$id'; controleer de magazijnen-config van het magazijnregister",
                Response.Status.BAD_GATEWAY,
            )
        }
    }

    private companion object {
        private val log: Logger = Logger.getLogger(MagazijnRouter::class.java)
    }
}
