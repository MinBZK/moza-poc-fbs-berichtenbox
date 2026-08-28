package nl.rijksoverheid.moz.fbs.democonsole.simulator

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

/** De knoppen van het paneel die de gesimuleerde magazijnen aansturen. */
@Path("/api/demo/simulator")
@Produces(MediaType.APPLICATION_JSON)
class SimulatorResource(private val service: SimulatorService) {

    @GET
    @Path("/magazijnen")
    fun magazijnen(): List<SimulatorMagazijn> = service.magazijnen()

    @POST
    @Path("/actief/{aantal}")
    fun zetActief(@PathParam("aantal") aantal: Int): Map<String, Int> = try {
        service.zetActief(aantal)
    } catch (ex: IllegalArgumentException) {
        throw BadRequestException(ex.message, ex)
    }

    @POST
    @Path("/vullen")
    fun vullen(
        @QueryParam("perMagazijn") perMagazijn: Int?,
        @QueryParam("bijlageElke") bijlageElke: Int?,
    ): SeedUitkomst = service.vul(
        ontvangers = SimulatorService.ONDERNEMERS,
        berichtenPerMagazijn = perMagazijn ?: SimulatorService.STANDAARD_PER_MAGAZIJN,
        bijlageElke = bijlageElke ?: SimulatorService.STANDAARD_BIJLAGE_ELKE,
    )

    @POST
    @Path("/legen")
    fun legen(): Map<String, Int> = service.herstel()
}
