package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import nl.rijksoverheid.moz.fbs.democonsole.DemoConfig
import java.net.URI

/**
 * Eén REST-client per magazijn-URL uit `demo.magazijnen."<OIN>".url`, bij de eerste aanlevering
 * opgebouwd en daarna hergebruikt.
 *
 * Apart van [AanleverService] omdat dit de enige stap is die een draaiende omgeving nodig heeft:
 * met de bedrading hier kan het aanleveren zelf — inclusief hoe het elke faalmodus benoemt — met
 * vaste dubbels worden getest, zonder magazijn en zonder Docker.
 */
@ApplicationScoped
class MagazijnClients(config: DemoConfig) {

    private val perOin: Map<String, MagazijnAanleverClient> =
        config.magazijnen().mapValues { (_, magazijn) ->
            QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(magazijn.url()))
                .build(MagazijnAanleverClient::class.java)
        }

    /** De client voor die afzender-OIN, of null als er in deze omgeving geen adres voor staat. */
    operator fun get(magazijnOin: String): MagazijnAanleverClient? = perOin[magazijnOin]
}
