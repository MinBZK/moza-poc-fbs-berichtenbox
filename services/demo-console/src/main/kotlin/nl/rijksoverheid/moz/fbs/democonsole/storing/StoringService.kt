package nl.rijksoverheid.moz.fbs.democonsole.storing

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RestClient

/** Orkestreert de storingsknoppen naar Toxiproxy-admin-calls. */
@ApplicationScoped
class StoringService(@param:RestClient private val toxiproxy: ToxiproxyClient) {

    fun traag(proxy: String, latencyMs: Int) {
        controleer(toxiproxy.voegToxicToe(proxy, ToxicVerzoek("latency", mapOf("latency" to latencyMs))), "traag zetten van $proxy")
    }

    fun uit(proxy: String) {
        controleer(toxiproxy.zetProxy(proxy, ProxyPatch(enabled = false)), "uitschakelen van $proxy")
    }

    // Herstel: elke proxy weer aan, alle toxics weg.
    fun reset() {
        toxiproxy.proxies().forEach { (naam, status) ->
            if (!status.enabled) {
                controleer(toxiproxy.zetProxy(naam, ProxyPatch(enabled = true)), "inschakelen van $naam")
            }

            status.toxics.forEach { toxic ->
                controleer(toxiproxy.verwijderToxic(naam, toxic.name), "verwijderen toxic ${toxic.name} van $naam")
            }
        }
    }

    private fun controleer(response: Response, actie: String) {
        response.use {
            check(it.status in 200..299) { "Toxiproxy-fout bij $actie: HTTP ${it.status}" }
        }
    }
}
