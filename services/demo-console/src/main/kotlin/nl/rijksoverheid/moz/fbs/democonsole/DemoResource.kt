package nl.rijksoverheid.moz.fbs.democonsole

import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.democonsole.aanlever.AanleverService
import nl.rijksoverheid.moz.fbs.democonsole.dataset.Basisdataset
import nl.rijksoverheid.moz.fbs.democonsole.generator.DemoBerichtGenerator
import nl.rijksoverheid.moz.fbs.democonsole.legen.MagazijnDatabase
import kotlin.random.Random

@Path("/api/demo")
@Produces(MediaType.APPLICATION_JSON)
class DemoResource(
    private val basisdataset: Basisdataset,
    private val aanleverService: AanleverService,
    private val generator: DemoBerichtGenerator,
    private val magazijnDatabase: MagazijnDatabase,
) {

    @POST
    @Path("/legen")
    fun legen(): Map<String, Int> = magazijnDatabase.leegAlles()

    @GET
    @Path("/status")
    fun status(): Map<String, Int> = magazijnDatabase.aantallen()

    @POST
    @Path("/basisvulling")
    fun basisvulling(): Map<String, Int> {
        val opdrachten = basisdataset.laad()
        val geslaagd = aanleverService.leverAan(opdrachten)

        return mapOf("aangeboden" to opdrachten.size, "geslaagd" to geslaagd)
    }

    @POST
    @Path("/random")
    fun random(@QueryParam("aantal") @DefaultValue("10") aantal: Int): Map<String, Int> {
        val opdrachten = generator.genereer(aantal, Random.Default)
        val geslaagd = aanleverService.leverAan(opdrachten)

        return mapOf("aangeboden" to opdrachten.size, "geslaagd" to geslaagd)
    }
}
