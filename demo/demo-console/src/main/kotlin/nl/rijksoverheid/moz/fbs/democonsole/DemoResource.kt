package nl.rijksoverheid.moz.fbs.democonsole

import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
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
import nl.rijksoverheid.moz.fbs.democonsole.omgeving.OmgevingConfig
import nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorService
import kotlin.random.Random

@Path("/api/demo")
@Produces(MediaType.APPLICATION_JSON)
class DemoResource(
    private val basisdataset: Basisdataset,
    private val aanleverService: AanleverService,
    private val generator: DemoBerichtGenerator,
    private val magazijnDatabase: MagazijnDatabase,
    private val herstelService: HerstelService,
    private val simulatorService: SimulatorService,
    private val omgeving: OmgevingConfig,
) {

    /**
     * Leegt de twee echte magazijnen én de gesimuleerde. Zou dit alleen de echte raken, dan zou de
     * demo na een druk op de knop nog steeds honderd gevulde organisaties tonen — en dat is precies
     * het beeld dat "legen" hoort weg te halen.
     *
     * De echte magazijnen eerst, en de gesimuleerde alleen waar ze er zijn: een omgeving zonder
     * simulator liet deze knop anders gegarandeerd falen zónder iets te legen. Is er wél een
     * simulator die niet antwoordt, dan blijft dat een harde fout — dit antwoord draagt alleen
     * aantallen, dus een overgeslagen stap zou hier niet te zien zijn.
     */
    @POST
    @Path("/legen")
    fun legen(): Map<String, Int> {
        val geleegd = magazijnDatabase.leegAlles()

        return if (omgeving.simulator()) geleegd + simulatorService.herstel() else geleegd
    }

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
}
