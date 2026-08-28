package nl.rijksoverheid.moz.fbs.democonsole.storing

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * De storingsknoppen die op een netwerkverbinding werken: de twee echte magazijnen, de sessiecache,
 * de profielservice, de notificatiedienst en de aanmeld-webhook.
 *
 * Voor de gesimuleerde magazijnen zijn deze knoppen er niet, en dat is geen omissie. Een proxy kan
 * een verbinding alleen traag maken of dichtzetten; de simulator kan een magazijn traag, haperend,
 * kapot, onbereikbaar of weigerend maken, en per magazijn verschillend. Die kant zit bij
 * [nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorResource].
 *
 * A en B houden hun proxy omdat zij dragen wat de simulator niet doet — aanleveren, bijlagen, de
 * notificatie-outbox en FSC — en verschillende demo-scenario's gaan juist over het uitvallen van
 * zo'n magazijn.
 */
@Path("/api/demo/storing")
@Produces(MediaType.APPLICATION_JSON)
class StoringResource(private val storingService: StoringService) {

    @POST
    @Path("/magazijn/{ab}/traag")
    fun magazijnTraag(@PathParam("ab") ab: String): Map<String, String> {
        storingService.traag(magazijnProxy(ab), LATENCY_MS)

        return mapOf("status" to "magazijn-$ab traag (${LATENCY_MS}ms)")
    }

    @POST
    @Path("/magazijn/{ab}/uit")
    fun magazijnUit(@PathParam("ab") ab: String): Map<String, String> {
        storingService.uit(magazijnProxy(ab))

        return mapOf("status" to "magazijn-$ab uit")
    }

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

    private fun magazijnProxy(ab: String): String {
        if (ab != "a" && ab != "b") throw BadRequestException("magazijn moet 'a' of 'b' zijn, was: '$ab'")

        return "magazijn-$ab"
    }

    private companion object {
        const val LATENCY_MS = 6000
    }
}
