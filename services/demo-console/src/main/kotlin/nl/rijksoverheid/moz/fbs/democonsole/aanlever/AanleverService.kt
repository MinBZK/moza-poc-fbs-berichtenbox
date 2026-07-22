package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverOpdracht
import java.net.URI
import java.util.logging.Logger

/**
 * Levert opdrachten aan bij het juiste magazijn. De magazijn-URL's komen uit config
 * (`demo.magazijnen."<OIN>".url`); per URL wordt één REST-client gebouwd en hergebruikt.
 */
@ApplicationScoped
class AanleverService(config: MagazijnenConfig) {

    private val log = Logger.getLogger(AanleverService::class.java.name)

    private val clients: Map<String, MagazijnAanleverClient> =
        config.magazijnen().mapValues { (_, magazijn) ->
            QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(magazijn.url()))
                .build(MagazijnAanleverClient::class.java)
        }

    fun leverAan(opdrachten: List<AanleverOpdracht>): Int {
        var geslaagd = 0

        opdrachten.forEach { opdracht ->
            val client = clients[opdracht.magazijnOin]

            if (client == null) {
                log.warning("geen magazijn-URL voor OIN ${opdracht.magazijnOin} — opdracht overgeslagen")
                return@forEach
            }

            client.leverAan(opdracht.verzoek).use { response ->
                if (response.status == 201) {
                    geslaagd++
                } else {
                    log.warning(
                        "aanleveren gaf HTTP ${response.status} voor ontvanger ${opdracht.verzoek.ontvanger.waarde}",
                    )
                }
            }
        }

        return geslaagd
    }
}
