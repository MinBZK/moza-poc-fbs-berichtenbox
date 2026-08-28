package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger

/**
 * Zorgt dat elke geconfigureerde proxy op zijn instantie bestaat én naar de juiste upstream wijst.
 *
 * Op ZAD is er geen `proxies.json`: de inhoud van een attachment wordt daar ongewijzigd gemount, dus
 * zo'n bestand zou in elke preview de upstream van `test` noemen — een preview die stilzwijgend het
 * verkeer van een ánder magazijn afhandelt. De console maakt de proxies daarom zelf aan, met een
 * upstream die uit een alias komt, en aliassen kennen `$DEPLOYMENT_NAME` wél.
 *
 * **Waarom dit blijft draaien en niet alleen bij het starten.** Toxiproxy houdt zijn proxies in het
 * geheugen. Herstart die pod, dan is de keten dood: al het profiel-, notificatie-, aanmeld- en
 * Redis-verkeer loopt erdoorheen.
 *
 * **En waarom hij ook vergelijkt.** Een proxy die naar de verkeerde upstream wijst is precies de
 * faalwijze waarvoor dit mechanisme bestaat, en die ontstaat zodra iemand een upstream bijstelt
 * terwijl de Toxiproxy-pod blijft draaien: de console herstart, de proxy niet. Alleen aanmaken-wat-
 * ontbreekt zou die verouderde proxy eeuwig laten staan, zonder een regel in de log.
 *
 * Een proxy die klopt blijft ongemoeid, ook een bewust uitgezette — alleen een ontbrekende of
 * afgeweken proxy wordt (her)bouwd. Dat een herbouw hem weer inschakelt is juist: zijn definitie is
 * dan veranderd, en de keten hoort met de nieuwe upstream te lopen.
 *
 * Lokaal is dit een no-op: compose zet de proxies uit `toxiproxy/proxies.json`, en die komen overeen
 * met dezelfde configuratie (bewaakt door `ToxiproxyProxiesConsistentieTest`).
 */
@ApplicationScoped
class ProxyBootstrap(private val register: ToxiproxyRegister, config: ToxiproxyConfig) {

    private val log = Logger.getLogger(ProxyBootstrap::class.java)

    private val definities = ProxyDefinities(config)

    fun bijStart(@Observes start: StartupEvent) {
        // Alleen hier, niet elke ronde: een ontbrekende listen of upstream verandert niet vanzelf,
        // en dezelfde waarschuwing om de dertig seconden verbergt de rest van de log.
        val onvolledig = definities.onvolledig()

        if (onvolledig.isNotEmpty()) {
            log.warnf(
                "Geen listen/upstream voor proxy %s; die wordt niet aangemaakt en zijn stroom loopt nergens doorheen.",
                onvolledig.joinToString(", "),
            )
        }

        reconcile()
    }

    // SKIP: een instantie die niet antwoordt houdt zijn ronde vast tot de timeout, en dan mag de
    // volgende ronde er niet bovenop komen.
    @Scheduled(every = "{toxiproxy.reconcile-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun reconcile() {
        // Per instantie, niet per proxy: dat scheelt in de normale ronde één GET in plaats van een
        // POST per proxy, en lokaal staan alle zes op dezelfde Toxiproxy.
        definities.alle().groupBy { register.client(it.naam) }.forEach { (instantie, gewenst) ->
            // Per instantie vangen: die staan op ZAD in verschillende projecten, dus één
            // onbereikbare Toxiproxy mag de overige niet ongemoeid laten.
            try {
                verzoen(instantie, gewenst)
            } catch (fout: Exception) {
                log.warnf(
                    "Proxies %s niet te verzoenen: %s",
                    gewenst.joinToString(", ") { it.naam },
                    fout.message ?: fout::class.simpleName,
                )
            }
        }
    }

    private fun verzoen(instantie: ToxiproxyClient, gewenst: List<ProxyDefinitie>) {
        val bestaand = instantie.proxies()

        gewenst.forEach { definitie ->
            val huidig = bestaand[definitie.naam]

            when {
                huidig == null -> maak(instantie, definitie)

                afgeweken(huidig, definitie) -> {
                    log.warnf(
                        "Proxy %s wees naar %s op %s in plaats van %s op %s; opnieuw gebouwd.",
                        definitie.naam,
                        huidig.upstream,
                        huidig.listen,
                        definitie.upstream,
                        definitie.listen,
                    )
                    controleer(instantie.verwijderProxy(definitie.naam), "verwijderen van ${definitie.naam}")
                    maak(instantie, definitie)
                }
            }
        }
    }

    // De listen-vergelijking gaat op de poort en niet op de hele string: Toxiproxy geeft terug
    // waaraan hij gebónden is, dus `0.0.0.0:18089` komt terug als `[::]:18089`. Letterlijk
    // vergelijken zou elke proxy elke ronde afgeweken noemen en hem blijven herbouwen.
    private fun afgeweken(huidig: ProxyStatus, definitie: ProxyDefinitie): Boolean =
        huidig.upstream != definitie.upstream || poort(huidig.listen) != poort(definitie.listen)

    private fun poort(adres: String): String = adres.substringAfterLast(':')

    private fun maak(instantie: ToxiproxyClient, definitie: ProxyDefinitie) {
        val verzoek = ProxyVerzoek(definitie.naam, definitie.listen, definitie.upstream)

        instantie.maakProxy(verzoek).use { respons ->
            when {
                respons.status in 200..299 ->
                    log.infof("Proxy %s aangemaakt: %s -> %s", definitie.naam, definitie.listen, definitie.upstream)

                // Een race met een andere ronde of met compose die hem net zette; de volgende ronde
                // vergelijkt hem alsnog.
                respons.status == Response.Status.CONFLICT.statusCode -> Unit

                else ->
                    log.warnf("Proxy %s niet aangemaakt: HTTP %d", definitie.naam, respons.status)
            }
        }
    }

    private fun controleer(respons: Response, actie: String) {
        respons.use {
            check(it.status in 200..299) { "Toxiproxy-fout bij $actie: HTTP ${it.status}" }
        }
    }
}
