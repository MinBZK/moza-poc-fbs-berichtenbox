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

    @POST
    @Path("/random")
    fun random(@QueryParam("aantal") @DefaultValue("10") aantal: Int): AanleverResultaat =
        aanleverService.leverAan(generator.genereer(aantal, Random.Default))

    /**
     * Berichten voor één aangewezen persona, zodat een demonstratie niet hoeft af te wachten of de
     * willekeur ze bij de ondernemer legt die op het scherm staat. Op de persona-`id` en niet op
     * zijn identificatienummer: een BSN hoort niet in een URL, ook niet in een demo.
     *
     * De twee bedieningsfouten worden hier afgevangen en niet met `require()`: [DemoFoutMapper]
     * vertaalt alleen een `WebApplicationException` naar zijn eigen status, dus een `require()`
     * zou een verkeerd ingevulde parameter als HTTP 500 tonen. `@DefaultValue` hoort bij dezelfde
     * afspraak: zonder die annotatie injecteert JAX-RS `null` in een niet-nullable parameter en
     * gooit Kotlin een `NullPointerException` vóór de eerste regel hieronder — ook een 500.
     */
    @POST
    @Path("/bericht")
    fun bericht(
        @QueryParam("persona") @DefaultValue("") persona: String,
        @QueryParam("aantal") @DefaultValue("1") aantal: Int,
    ): AanleverResultaat {
        if (persona.isBlank()) throw BadRequestException(KIES_EEN_PERSONA)

        // Nul zou anders een groene melding "0 van 0 aangeleverd" opleveren voor een actie die niets
        // deed, en een grote waarde levert evenveel synchrone aanleveringen op. De browser bewaakt
        // dezelfde grenzen, maar het runbook en Bruno roepen dit adres rechtstreeks aan.
        if (aantal !in 1..MAX_BERICHTEN) {
            throw BadRequestException("aantal moet tussen 1 en $MAX_BERICHTEN liggen, was: $aantal")
        }

        val opdrachten = generator.genereerVoor(persona, aantal, Random.Default)
            ?: throw NotFoundException("onbekende persona '$persona'; $KIES_EEN_PERSONA")

        return aanleverService.leverAan(opdrachten)
    }

    private companion object {

        /** Spiegelt de `max` van het aantal-veld in `index.html`; hoger is voor een demo geen realistische vraag. */
        const val MAX_BERICHTEN = 100

        const val KIES_EEN_PERSONA = "kies een persona uit berichtPersonas van /api/demo/omgeving"
    }
}
