package nl.rijksoverheid.moz.fbs.democonsole.storing

import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger

/**
 * Zorgt dat elke geconfigureerde proxy op zijn instantie bestaat.
 *
 * Op ZAD is er geen `proxies.json`: de inhoud van een attachment wordt daar ongewijzigd gemount, dus
 * zo'n bestand zou in elke preview de upstream van `test` noemen — een preview die stilzwijgend het
 * verkeer van een ánder magazijn afhandelt. De console maakt de proxies daarom zelf aan, met een
 * upstream die uit een alias komt, en aliassen kennen `$DEPLOYMENT_NAME` wél.
 *
 * **Waarom dit blijft draaien en niet alleen bij het starten.** Toxiproxy houdt zijn proxies in het
 * geheugen. Herstart die pod, dan is de keten dood: al het profiel-, notificatie-, aanmeld- en
 * Redis-verkeer loopt erdoorheen. De reconcile maakt alleen ontbrekende proxies aan — een bestaande
 * blijft staan, ook als hij bewust uitgezet is — dus alleen een leeggeraakte instantie wordt
 * opnieuw gevuld.
 *
 * Lokaal is dit een no-op: compose zet de proxies uit `toxiproxy/proxies.json` en Toxiproxy
 * antwoordt dan 409 op elk verzoek.
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
        definities.alle().forEach { definitie ->
            // Per proxy vangen: de instanties staan op ZAD in verschillende projecten, dus één
            // onbereikbare Toxiproxy mag de overige niet ongemoeid laten.
            try {
                maak(definitie)
            } catch (fout: Exception) {
                log.warnf("Proxy %s niet aan te maken: %s", definitie.naam, fout.message ?: fout::class.simpleName)
            }
        }
    }

    private fun maak(definitie: ProxyDefinitie) {
        val verzoek = ProxyVerzoek(definitie.naam, definitie.listen, definitie.upstream)

        register.client(definitie.naam).maakProxy(verzoek).use { respons ->
            when {
                respons.status in 200..299 ->
                    log.infof("Proxy %s aangemaakt: %s -> %s", definitie.naam, definitie.listen, definitie.upstream)

                // Toxiproxy antwoordt 409 zodra de naam al bestaat. Dat is de normale uitkomst van
                // elke ronde na de eerste, en van elke ronde lokaal.
                respons.status == Response.Status.CONFLICT.statusCode -> Unit

                else ->
                    log.warnf("Proxy %s niet aangemaakt: HTTP %d", definitie.naam, respons.status)
            }
        }
    }
}
