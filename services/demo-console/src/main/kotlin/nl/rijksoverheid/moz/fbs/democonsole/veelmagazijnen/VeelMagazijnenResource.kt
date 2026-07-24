package nl.rijksoverheid.moz.fbs.democonsole.veelmagazijnen

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/demo/veel-magazijnen")
@Produces(MediaType.APPLICATION_JSON)
class VeelMagazijnenResource(private val service: VeelMagazijnenService) {

    @POST
    @Path("/actief/{k}")
    fun actief(@PathParam("k") k: Int): Map<String, Int> =
        try {
            service.zetActief(k)
        } catch (fout: IllegalArgumentException) {
            throw BadRequestException(fout.message)
        }

    @POST
    @Path("/reset")
    fun reset(): Map<String, Int> = service.reset()
}
