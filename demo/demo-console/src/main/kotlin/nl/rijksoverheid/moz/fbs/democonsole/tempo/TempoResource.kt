package nl.rijksoverheid.moz.fbs.democonsole.tempo

import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

@Path("/api/demo/tempo")
@Produces(MediaType.APPLICATION_JSON)
class TempoResource(private val tempoService: TempoService) {

    @GET
    fun status(): TempoStatus = tempoService.status()

    @POST
    @Path("/start")
    fun start(@QueryParam("interval") @DefaultValue("10") interval: Int): TempoStatus =
        tempoService.start(interval)

    @POST
    @Path("/stop")
    fun stop(): TempoStatus = tempoService.stop()
}
