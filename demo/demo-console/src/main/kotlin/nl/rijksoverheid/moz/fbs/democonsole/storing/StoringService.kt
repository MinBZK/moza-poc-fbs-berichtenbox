package nl.rijksoverheid.moz.fbs.democonsole.storing

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Orkestreert de storingsknoppen naar Toxiproxy-admin-calls. */
@ApplicationScoped
class StoringService(private val register: ToxiproxyRegister) {

    fun traag(proxy: String, latencyMs: Int) {
        controleer(
            register.client(proxy).voegToxicToe(proxy, ToxicVerzoek("latency", mapOf("latency" to latencyMs))),
            "traag zetten van $proxy",
        )
    }

    fun uit(proxy: String) {
        controleer(register.client(proxy).zetProxy(proxy, ProxyPatch(enabled = false)), "uitschakelen van $proxy")
    }

    // Herstel: elke proxy op elke instantie weer aan, alle toxics weg.
    fun reset() {
        register.instanties().forEach { instantie ->
            herstel(instantie)
        }
    }

    private fun herstel(instantie: ToxiproxyClient) {
        val proxies = instantie.proxies()

        // Toxiproxy start gezond op met nul proxies zodra zijn configuratie ontbreekt of misvormd
        // is. Al het verkeer van die stroom loopt erdoorheen, dus dan is de keten dood — en juist
        // deze knop moet dat aanwijzen in plaats van "alles normaal" te bevestigen.
        check(proxies.isNotEmpty()) {
            "Toxiproxy kent geen enkele proxy: de keten loopt nergens doorheen. Controleer proxies.json en herstart toxiproxy."
        }

        proxies.forEach { (naam, status) ->
            if (!status.enabled) {
                controleer(instantie.zetProxy(naam, ProxyPatch(enabled = true)), "inschakelen van $naam")
            }

            status.toxics.forEach { toxic ->
                controleer(instantie.verwijderToxic(naam, toxic.name), "verwijderen toxic ${toxic.name} van $naam")
            }
        }
    }

    private fun controleer(response: Response, actie: String) {
        response.use {
            check(it.status in 200..299) { "Toxiproxy-fout bij $actie: HTTP ${it.status}" }
        }
    }
}
