package nl.rijksoverheid.moz.fbs.democonsole.tempo

import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.democonsole.heelGetal

@Path("/api/demo/tempo")
@Produces(MediaType.APPLICATION_JSON)
class TempoResource(private val tempoService: TempoService) {

    @GET
    fun status(): TempoStatus = tempoService.status()

    /**
     * Het interval komt als tekst binnen en gaat door [heelGetal]; daar staat waarom deze parameter
     * geen `Int` is. De grenzen zijn die van [TempoService], die ze zelf nog eens toetst: dat is de
     * invariant van de stroom, niet van dit adres.
     */
    @POST
    @Path("/start")
    fun start(@QueryParam("interval") @DefaultValue("") interval: String): TempoStatus =
        tempoService.start(
            heelGetal("interval", interval, STANDAARD_INTERVAL, TempoService.MIN_INTERVAL..TempoService.MAX_INTERVAL),
        )

    @POST
    @Path("/stop")
    fun stop(): TempoStatus = tempoService.stop()

    private companion object {

        /** Wat het paneel als `value` in het veld `tempoInterval` toont. */
        const val STANDAARD_INTERVAL = 10
    }
}
