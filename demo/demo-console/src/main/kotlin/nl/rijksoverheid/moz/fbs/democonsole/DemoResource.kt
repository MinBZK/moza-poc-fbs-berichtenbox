package nl.rijksoverheid.moz.fbs.democonsole

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverResultaat
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.generator.DemoBerichtGenerator
import nl.rijksoverheid.moz.fbs.democonsole.herstel.HerstelResultaat
import nl.rijksoverheid.moz.fbs.democonsole.herstel.HerstelService
import nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase
import nl.rijksoverheid.moz.fbs.democonsole.simulator.GesimuleerdHerstel
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorService
import kotlin.random.Random

/**
 * Wat het legen weghaalde. Twee benoemde velden en niet één platte map: de echte magazijnen tellen
 * per magazijn wat er stónd, de simulator telt zijn totalen. Samengevoegd las de melding als
 * "RVO 240, Bel.dienst 180, berichten 7840, magazijnen 98" — waarin "berichten 7840" eruitziet als
 * een magazijn dat nog vol staat, precies het tegenovergestelde van wat de knop deed.
 */
data class LeegAntwoord(val magazijnen: Map<String, Int>, val gesimuleerd: GesimuleerdHerstel)

@Path("/api/demo")
@Produces(MediaType.APPLICATION_JSON)
class DemoResource(
    private val basisdataset: Basisdataset,
    private val aanleverService: AanleverService,
    private val generator: DemoBerichtGenerator,
    private val magazijnDatabase: MagazijnDatabase,
    private val herstelService: HerstelService,
    private val simulatorService: SimulatorService,
) {

    /**
     * Leegt de twee echte magazijnen én de gesimuleerde. Zou dit alleen de echte raken, dan zou de
     * demo na een druk op de knop nog steeds honderd gevulde organisaties tonen — en dat is precies
     * het beeld dat "legen" hoort weg te halen.
     *
     * De echte magazijnen eerst, en de gesimuleerde als deelstap die mag ontbreken: een omgeving
     * zonder simulator liet deze knop anders gegarandeerd falen zónder iets te legen.
     */
    @POST
    @Path("/legen")
    fun legen(): LeegAntwoord = LeegAntwoord(magazijnDatabase.leegAlles(), simulatorService.herstelZoMogelijk())

    @POST
    @Path("/herstel")
    fun herstel(): HerstelResultaat = herstelService.herstel()

    @GET
    @Path("/status")
    fun status(): Map<String, Int> = magazijnDatabase.aantallen()

    @POST
    @Path("/basisvulling")
    fun basisvulling(): AanleverResultaat = aanleverService.leverAan(basisdataset.laad())

    /**
     * Een burst willekeurige berichten, om de demo-omgeving in één klik te vullen.
     *
     * Het aantal komt als tekst binnen en gaat door [heelGetal]; daar staat waarom deze grenzen er
     * zijn en waarom de parameter geen `Int` is.
     */
    @POST
    @Path("/random")
    fun random(@QueryParam("aantal") @DefaultValue("") aantal: String): AanleverResultaat {
        val gevraagd = heelGetal("aantal", aantal, STANDAARD_RANDOM, 1..MAX_RANDOM_BERICHTEN)

        return aanleverService.leverAan(generator.genereer(gevraagd, Random.Default))
    }

    /**
     * Berichten voor één aangewezen persona, zodat een demonstratie niet hoeft af te wachten of de
     * willekeur ze bij de ondernemer legt die op het scherm staat. Op de persona-`id` en niet op
     * zijn identificatienummer: een BSN hoort niet in een URL, ook niet in een demo.
     *
     * Elke bedieningsfout wordt hier afgevangen en niet met `require()`: [DemoFoutMapper] vertaalt
     * alleen een `WebApplicationException` naar zijn eigen status, dus een `require()` zou een
     * verkeerd ingevulde parameter als HTTP 500 tonen.
     *
     * Vandaar ook `@DefaultValue("")` op `persona`: een ontbrekende parameter wordt anders `null` in
     * een niet-nullable parameter, wat Kotlin met een `NullPointerException` beantwoordt vóór de
     * eerste regel hieronder. Voor `aantal` doet [heelGetal] hetzelfde werk, met daar de uitleg
     * waarom die parameter tekst is en geen `Int`.
     */
    @POST
    @Path("/bericht")
    fun bericht(
        @QueryParam("persona") @DefaultValue("") persona: String,
        @QueryParam("aantal") @DefaultValue("") aantal: String,
    ): AanleverResultaat {
        if (persona.isBlank()) throw BadRequestException(KIES_EEN_PERSONA)

        val gevraagd = heelGetal("aantal", aantal, STANDAARD_GERICHT, 1..MAX_GERICHTE_BERICHTEN)

        val opdrachten = generator.genereerVoor(persona, gevraagd, Random.Default)
            ?: throw NotFoundException("onbekende persona '$persona'; $KIES_EEN_PERSONA")

        return aanleverService.leverAan(opdrachten)
    }

    internal companion object {

        /**
         * Hoger is voor een demo geen realistische vraag, en elke aanlevering is een synchrone
         * ronde. Spiegelt de `max` van het veld `berichtAantal` in `index.html`; `PaneelPadenTest`
         * bewaakt dat die twee gelijk blijven.
         */
        const val MAX_GERICHTE_BERICHTEN = 100

        /**
         * Ruimer dan een gericht bericht: hiermee wordt een lege omgeving gevuld, en dan is een paar
         * honderd berichten een normale vraag. Spiegelt de `max` van het veld `aantal`.
         */
        const val MAX_RANDOM_BERICHTEN = 500

        // Waar een aanroep zonder aantal op terugvalt: wat het paneel in het bijbehorende veld
        // voorinvult, zodat het adres hetzelfde doet als een klik op de knop.
        private const val STANDAARD_RANDOM = 10

        private const val STANDAARD_GERICHT = 1

        private const val KIES_EEN_PERSONA = "kies een persona uit berichtPersonas van /api/demo/omgeving"
    }
}
