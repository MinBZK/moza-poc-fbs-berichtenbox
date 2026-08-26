package nl.rijksoverheid.moz.fbs.democonsole.sessie

import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/demo/sessie")
@Produces(MediaType.APPLICATION_JSON)
class SessieResource(private val sessieService: SessieService) {

    @POST
    @Path("/verlopen")
    fun verlopen(): Map<String, Int> = mapOf("gewisteKeys" to sessieService.laatSessiesVerlopen())
}
