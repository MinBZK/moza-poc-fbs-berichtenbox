package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI
import java.util.concurrent.TimeUnit

/** Bedrading: één REST-client per uniek Toxiproxy-adres, gebouwd zoals `MagazijnClients` dat doet. */
@ApplicationScoped
class ToxiproxyRegister(config: ToxiproxyConfig) {

    private val adressen = ToxiproxyAdressen(config)

    private val definities = ProxyDefinities(config)

    private val perAdres: Map<String, ToxiproxyClient> =
        adressen.unieke().associateWith { adres ->
            QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(adres))
                // Korte timeouts, want de default van 15 seconden is hier schadelijk. Ontbreekt de
                // netwerkregel naar een admin-API, dan wórden de pakketten gedropt en niet
                // geweigerd: elke aanroep blijft dan hangen tot de timeout. ProxyBootstrap loopt bij
                // het starten alle instanties serieel langs, dus elke onbereikbare kost die
                // wachttijd vóórdat de console zijn poort opent. Twee seconden is ruim voor een
                // admin-API in hetzelfde cluster en houdt die stapeling kort.
                .connectTimeout(CONNECT_TIMEOUT_SECONDEN, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDEN, TimeUnit.SECONDS)
                .build(ToxiproxyClient::class.java)
        }

    /**
     * De proxies waarover deze console iets te melden heeft: alleen de volledig ingerichte.
     *
     * Uit [ProxyDefinities] en niet uit de adressen alleen. Een proxy met een url maar zonder
     * bruikbare listen of upstream wordt door [ProxyBootstrap] nooit aangemaakt; zou hij hier tóch
     * meetellen, dan toont het paneel zijn knop, meldt de status "onbekend" — niet te onderscheiden
     * van een Toxiproxy die plat ligt — en levert de knop een fout op Toxiproxy's 404.
     */
    fun namen(): Set<String> = definities.alle().map { it.naam }.toSet()

    fun client(proxy: String): ToxiproxyClient = perAdres.getValue(adressen.adres(proxy))

    private companion object {

        const val CONNECT_TIMEOUT_SECONDEN = 2L

        const val READ_TIMEOUT_SECONDEN = 5L
    }
}
