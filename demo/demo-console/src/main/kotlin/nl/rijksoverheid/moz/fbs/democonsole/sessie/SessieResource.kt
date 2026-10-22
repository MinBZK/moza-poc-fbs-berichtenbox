package nl.rijksoverheid.moz.fbs.democonsole.sessie

import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/demo/sessie")
@Produces(MediaType.APPLICATION_JSON)
class SessieResource(private val sessieService: SessieService) {

    /**
     * `gewisteKeys` heeft twee lezers: `bediening.js` toont het aantal, en `demo/meet-fanout.sh`
     * herkent er een geslaagd legen aan — een inlogmuur kan met 200 antwoorden, maar niet met dit
     * veld. Hernoem je het, pas dan beide mee aan.
     */
    @POST
    @Path("/verlopen")
    fun verlopen(): Map<String, Int> = mapOf("gewisteKeys" to sessieService.laatSessiesVerlopen())
}
