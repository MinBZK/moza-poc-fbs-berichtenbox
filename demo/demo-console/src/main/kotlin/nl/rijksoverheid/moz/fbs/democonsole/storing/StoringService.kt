package nl.rijksoverheid.moz.fbs.democonsole.storing

import com.fasterxml.jackson.annotation.JsonValue
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import java.util.logging.Logger

/**
 * Wat er op een proxy aanstaat. [ONBEKEND] is geen sierstand: bij een instantie die niet antwoordt
 * of een geconfigureerde proxy die Toxiproxy niet kent, weten we juist níét wat er aanstaat. Dat
 * als "normaal" tonen verbergt precies de misconfiguratie of uitval die je zoekt.
 */
enum class Storingstoestand(@get:JsonValue val waarde: String) {
    NORMAAL("normaal"),
    TRAAG("traag"),
    UIT("uit"),
    ONBEKEND("onbekend"),
}

/** Orkestreert de storingsknoppen naar Toxiproxy-admin-calls. */
@ApplicationScoped
class StoringService(private val register: ToxiproxyRegister) {

    private val log = Logger.getLogger(StoringService::class.java.name)

    /**
     * Wat er nú per geconfigureerde proxy aanstaat. Het paneel toont dit doorlopend, zodat een
     * blijven-staande storing zichtbaar is zonder ernaar te vragen — een demo loopt vaker stuk op
     * een vergeten reset dan op een vergeten knop.
     *
     * Eén aanroep per instantie in plaats van per proxy, want dit wordt gepolld. Een instantie die
     * niet antwoordt levert ONBEKEND voor uitsluitend zijn eigen proxies; de overige houden hun
     * echte toestand.
     */
    fun status(): Map<String, Storingstoestand> =
        register.namen()
            .groupBy { register.client(it) }
            .flatMap { (instantie, namen) -> toestanden(instantie, namen) }
            .sortedBy { (naam, _) -> naam }
            .toMap()

    fun traag(proxy: String, latencyMs: Int) {
        controleer(
            register.client(proxy).voegToxicToe(proxy, ToxicVerzoek("latency", mapOf("latency" to latencyMs))),
            "traag zetten van $proxy",
        )
    }

    fun uit(proxy: String) {
        controleer(register.client(proxy).zetProxy(proxy, ProxyPatch(enabled = false)), "uitschakelen van $proxy")
    }

    // Herstel: elke proxy op elke instantie weer aan, alle toxics weg. Elke instantie krijgt zijn
    // eigen poging, los van de andere: op een gedeelde omgeving met meerdere instanties mag één
    // kapotte instantie de storingen op de overige, gezonde instanties niet laten staan. Fouten
    // worden verzameld en pas aan het eind gemeld, geïndexeerd naar instantie (zodat twee identieke
    // meldingen te onderscheiden blijven) en met een terugval op de exceptienaam wanneer de fout
    // zelf geen message heeft — anders valt zo'n fout stil uit de lijst en meldt reset() ten
    // onrechte dat alles gelukt is.
    fun reset() {
        val instanties = register.instanties()

        check(instanties.isNotEmpty()) {
            "Geen enkele proxy geconfigureerd: er is niets om te herstellen. Controleer de TOXIPROXY_*_URL-configuratie."
        }

        val fouten = instanties.withIndex().mapNotNull { (index, instantie) ->
            runCatching { herstel(instantie) }.exceptionOrNull()?.let { fout ->
                "instantie ${index + 1}: ${fout.message ?: fout::class.simpleName}"
            }
        }

        check(fouten.isEmpty()) {
            "Herstel is niet overal gelukt:\n" + fouten.joinToString("\n")
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

    private fun toestanden(
        instantie: ToxiproxyClient,
        namen: List<String>,
    ): List<Pair<String, Storingstoestand>> {
        val uitkomst = runCatching { instantie.proxies() }

        // Het paneel toont ONBEKEND, maar zonder deze regel is nergens meer terug te vinden of dat
        // een weggevallen instantie, een timeout of een foutstatus was.
        uitkomst.exceptionOrNull()?.let { fout ->
            log.warning("Toxiproxy niet uit te lezen voor $namen: ${fout.message ?: fout::class.simpleName}")
        }

        val proxies = uitkomst.getOrNull() ?: return namen.map { it to Storingstoestand.ONBEKEND }

        return namen.map { it to toestand(proxies[it]) }
    }

    // Uit wint van traag zodra beide gelden — na eerst traag en daarna uit indrukken suggereert
    // "traag" dat er nog verkeer doorheen komt, en dat is de storing niet die je toont.
    private fun toestand(status: ProxyStatus?): Storingstoestand = when {
        status == null -> Storingstoestand.ONBEKEND
        !status.enabled -> Storingstoestand.UIT
        status.toxics.isNotEmpty() -> Storingstoestand.TRAAG
        else -> Storingstoestand.NORMAAL
    }

    private fun controleer(response: Response, actie: String) {
        response.use {
            check(it.status in 200..299) { "Toxiproxy-fout bij $actie: HTTP ${it.status}" }
        }
    }
}
