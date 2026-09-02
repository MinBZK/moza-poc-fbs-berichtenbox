package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI
import java.util.concurrent.TimeUnit

/** Bedrading: één REST-client per uniek Toxiproxy-adres, gebouwd zoals `AanleverService` dat doet. */
@ApplicationScoped
class ToxiproxyRegister(config: ToxiproxyConfig) {

    private val adressen = ToxiproxyAdressen(config)

    private val perAdres: Map<String, ToxiproxyClient> =
        adressen.unieke().associateWith { adres ->
            QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(adres))
                // Korte timeouts, want de default van 15 seconden is hier schadelijk. Ontbreekt de
                // netwerkregel naar een admin-API, dan wórden de pakketten gedropt en niet
                // geweigerd: elke aanroep blijft dan hangen tot de timeout. ProxyBootstrap loopt bij
                // het starten alle instanties serieel langs, dus vier onbereikbare adressen kosten
                // vier keer die wachttijd vóórdat de console zijn poort opent. Twee seconden is ruim
                // voor een admin-API in hetzelfde cluster, en houdt die stapeling binnen tien
                // seconden.
                .connectTimeout(CONNECT_TIMEOUT_SECONDEN, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDEN, TimeUnit.SECONDS)
                .build(ToxiproxyClient::class.java)
        }

    fun namen(): Set<String> = adressen.namen()

    fun client(proxy: String): ToxiproxyClient = perAdres.getValue(adressen.adres(proxy))

    private companion object {

        const val CONNECT_TIMEOUT_SECONDEN = 2L

        const val READ_TIMEOUT_SECONDEN = 5L
    }
}
