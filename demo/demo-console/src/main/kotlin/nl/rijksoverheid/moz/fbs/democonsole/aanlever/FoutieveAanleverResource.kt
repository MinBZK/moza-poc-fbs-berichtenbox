package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/demo/foutieve-aanlevering")
@Produces(MediaType.APPLICATION_JSON)
class FoutieveAanleverResource(private val service: FoutieveAanleverService) {

    @POST
    fun stuur(): FoutResultaat = service.stuurOngeldig()
}
