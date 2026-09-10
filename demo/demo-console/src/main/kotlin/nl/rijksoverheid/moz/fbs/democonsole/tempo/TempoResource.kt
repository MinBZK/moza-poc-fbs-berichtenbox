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
     * invariant van de stroom, niet van dit adres. De eenheid gaat mee de melding in, want "tussen
     * 1 en 3600" leest zonder die eenheid net zo goed als milliseconden of als een aantal berichten.
     */
    @POST
    @Path("/start")
    fun start(@QueryParam("interval") @DefaultValue("") interval: String): TempoStatus =
        tempoService.start(
            heelGetal(
                naam = "interval",
                waarde = interval,
                standaard = STANDAARD_INTERVAL,
                grenzen = TempoService.MIN_INTERVAL..TempoService.MAX_INTERVAL,
                eenheid = "seconden",
            ),
        )

    @POST
    @Path("/stop")
    fun stop(): TempoStatus = tempoService.stop()

    internal companion object {

        /** Spiegelt de `value` van het veld `tempoInterval` in `index.html`; `PaneelPadenTest` bewaakt dat. */
        const val STANDAARD_INTERVAL = 10
    }
}
