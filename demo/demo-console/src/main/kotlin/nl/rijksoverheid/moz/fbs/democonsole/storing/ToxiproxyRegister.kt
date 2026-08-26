package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI

/** Bedrading: één REST-client per uniek Toxiproxy-adres, gebouwd zoals `AanleverService` dat doet. */
@ApplicationScoped
class ToxiproxyRegister(config: ToxiproxyConfig) {

    private val adressen = ToxiproxyAdressen(config)

    private val perAdres: Map<String, ToxiproxyClient> =
        adressen.unieke().associateWith { adres ->
            QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(adres))
                .build(ToxiproxyClient::class.java)
        }

    fun namen(): Set<String> = adressen.namen()

    fun client(proxy: String): ToxiproxyClient = perAdres.getValue(adressen.adres(proxy))

    fun instanties(): Collection<ToxiproxyClient> = perAdres.values
}
