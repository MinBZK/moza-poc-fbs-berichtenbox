package nl.rijksoverheid.moz.fbs.democonsole.storing

import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * De storingsknoppen voor de infrastructuur rondom de keten: de sessiecache, de profielservice, de
 * notificatiedienst en de aanmeld-webhook.
 *
 * De magazijnen staan er niet meer bij. Een proxy kan een verbinding alleen traag maken of
 * dichtzetten; de magazijn-simulator kan een magazijn traag, haperend, kapot, onbereikbaar of
 * weigerend maken, en per magazijn verschillend. Die knoppen zitten daarom bij
 * [nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorResource].
 */
@Path("/api/demo/storing")
@Produces(MediaType.APPLICATION_JSON)
class StoringResource(private val storingService: StoringService) {

    @POST
    @Path("/reset")
    fun reset(): Map<String, String> {
        storingService.reset()

        return mapOf("status" to "alles normaal")
    }

    @POST
    @Path("/{proxy}/uit")
    fun infraUit(@PathParam("proxy") proxy: String): Map<String, String> {
        storingService.uit(proxy)

        return mapOf("status" to "$proxy uit")
    }
}
