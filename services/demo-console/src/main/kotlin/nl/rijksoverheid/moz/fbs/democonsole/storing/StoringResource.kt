package nl.rijksoverheid.moz.fbs.democonsole.storing

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

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
        if (proxy !in INFRA_PROXIES) {
            throw BadRequestException("onbekende proxy '$proxy'; toegestaan: $INFRA_PROXIES")
        }

        storingService.uit(proxy)

        return mapOf("status" to "$proxy uit")
    }

    private fun magazijnProxy(ab: String): String {
        if (ab != "a" && ab != "b") throw BadRequestException("magazijn moet 'a' of 'b' zijn, was: '$ab'")

        return "magazijn-$ab"
    }

    private companion object {

        const val LATENCY_MS = 6000

        // Alleen infra-proxies waarvoor een knop bestaat; magazijn a/b lopen via hun eigen
        // getypeerde endpoints. Voorkomt dat het paneel een willekeurige proxy uitschakelt.
        val INFRA_PROXIES = setOf("profiel", "redis", "notificatie", "aanmeld")
    }
}
