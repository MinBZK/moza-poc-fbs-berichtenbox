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

    /**
     * Zet er een latency-toxic op en geeft terug wat er daarna daadwerkelijk staat.
     *
     * Teruglezen omdat Toxiproxy een toxic ook op een uitgezette proxy aanneemt, met HTTP 200: de
     * toxic staat er dan wel, maar er komt geen byte doorheen. Zonder deze regel meldde het paneel
     * "traag" boven een verbinding die dicht staat, en sprak het zichzelf tegen met zijn eigen chip.
     */
    fun traag(proxy: String, latencyMs: Int): Storingstoestand {
        controleer(
            register.client(proxy).voegToxicToe(proxy, ToxicVerzoek("latency", mapOf("latency" to latencyMs))),
            "traag zetten van $proxy",
            // 409 = deze toxic staat er al, want een tweede druk op dezelfde knop biedt hem opnieuw
            // aan. De gewenste toestand is dan juist bereikt; dat als fout melden zette het paneel
            // op "Mislukt" terwijl de chip ernaast onverminderd "traag" toonde.
            bereikt = Response.Status.CONFLICT.statusCode,
        )

        return toestandVan(proxy)
    }

    /** Zet de proxy uit en geeft terug wat er daarna staat; zie [traag] voor het waarom. */
    fun uit(proxy: String): Storingstoestand {
        controleer(register.client(proxy).zetProxy(proxy, ProxyPatch(enabled = false)), "uitschakelen van $proxy")

        return toestandVan(proxy)
    }

    private fun toestandVan(proxy: String): Storingstoestand = toestand(register.client(proxy).proxies()[proxy])

    // Herstel: elke proxy op elke instantie weer aan, alle toxics weg. Elke instantie krijgt zijn
    // eigen poging, los van de andere: op een gedeelde omgeving met meerdere instanties mag één
    // kapotte instantie de storingen op de overige, gezonde instanties niet laten staan. Fouten
    // worden verzameld en pas aan het eind gemeld, geïndexeerd naar instantie (zodat twee identieke
    // meldingen te onderscheiden blijven) en met een terugval op de exceptienaam wanneer de fout
    // zelf geen message heeft — anders valt zo'n fout stil uit de lijst en meldt reset() ten
    // onrechte dat alles gelukt is.
    fun reset() {
        val perInstantie = register.namen().groupBy { register.client(it) }

        check(perInstantie.isNotEmpty()) {
            "Geen enkele proxy geconfigureerd: er is niets om te herstellen. Controleer de TOXIPROXY_*_URL-configuratie."
        }

        val fouten = perInstantie.entries.withIndex().mapNotNull { (index, ingang) ->
            runCatching { herstel(ingang.key, ingang.value) }.exceptionOrNull()?.let { fout ->
                "instantie ${index + 1}: ${fout.message ?: fout::class.simpleName}"
            }
        }

        check(fouten.isEmpty()) {
            "Herstel is niet overal gelukt:\n" + fouten.joinToString("\n")
        }
    }

    private fun herstel(instantie: ToxiproxyClient, verwacht: List<String>) {
        val proxies = instantie.proxies()

        // Eerst goedmaken wat er wél staat, en per proxy vangen: één proxy die niet mee wil, mag de
        // storingen op alle proxies die erná komen niet laten staan. Alle meldingen samen, zodat
        // deze knop niet één probleem tegelijk prijsgeeft.
        val fouten = proxies.mapNotNull { (naam, status) ->
            runCatching { herstelEen(instantie, naam, status) }.exceptionOrNull()?.let { fout ->
                "$naam: ${fout.message ?: fout::class.simpleName}"
            }
        }

        check(fouten.isEmpty()) { "Niet elke proxy is teruggezet:\n" + fouten.joinToString("\n") }

        // Tegen de geconfigureerde namen aan houden en niet alleen tegen "kent er tenminste één".
        // Een proxy die Toxiproxy niet kent, bestaat niet: al het verkeer van die stroom loopt
        // erdoorheen, dus die stroom is dood. Herstellen-wat-er-is zou dat bevestigen met "alles
        // normaal", precies wanneer iemand deze knop indrukt omdat er al iets niet klopt. De twee
        // oorzaken verschillen per omgeving, vandaar beide in de melding: lokaal een ontbrekende of
        // misvormde proxies.json, op een gedeelde omgeving een Toxiproxy die net herstartte
        // (ProxyBootstrap zet hem bij de volgende ronde terug) of een admin-API die de console niet
        // bereikt.
        val ontbrekend = (verwacht - proxies.keys).sorted()

        check(ontbrekend.isEmpty()) {
            "Toxiproxy kent $ontbrekend niet: die stroom loopt nergens doorheen. Lokaal wijst dat op proxies.json; " +
                "op een gedeelde omgeving op een Toxiproxy die net herstartte, of op een onbereikbare admin-API."
        }
    }

    private fun herstelEen(instantie: ToxiproxyClient, naam: String, status: ProxyStatus) {
        if (!status.enabled) {
            controleer(instantie.zetProxy(naam, ProxyPatch(enabled = true)), "inschakelen van $naam")
        }

        status.toxics.forEach { toxic ->
            controleer(
                instantie.verwijderToxic(naam, toxic.name),
                "verwijderen toxic ${toxic.name} van $naam",
                // 404 = die toxic is er al niet meer. Dat is precies wat deze knop wilde bereiken,
                // en het gebeurt zodra iets anders hem tussen het uitlezen en het verwijderen
                // weghaalde.
                bereikt = Response.Status.NOT_FOUND.statusCode,
            )
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

    /**
     * [bereikt] is een statuscode waarmee Toxiproxy zegt dat de gewenste toestand er al is. Die
     * telt als geslaagd: de knop wilde een toestand, niet een verandering.
     */
    private fun controleer(response: Response, actie: String, bereikt: Int? = null) {
        response.use {
            check(it.status in 200..299 || it.status == bereikt) { "Toxiproxy-fout bij $actie: HTTP ${it.status}" }
        }
    }
}
