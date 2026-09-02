package nl.rijksoverheid.moz.fbs.democonsole.simulator

import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response

/**
 * Een beheerpad dat antwoordt zoals de magazijn-simulator dat doet wanneer het token ontbreekt:
 * 401 met een problem+json-body.
 *
 * Alleen op het test-classpath, en de beheerclient wijst er onder het testprofiel naartoe. Zo toont
 * [SimulatorBeheerClientFoutTest] aan dat [SimulatorBeheerFout] ook daadwerkelijk bedraad is — een
 * unittest op de mapper zelf zou een niet-geregistreerde provider niet opmerken, en dat is precies
 * de vergissing die deze mapper moet uitsluiten.
 */
@Path("/nep-simulator/beheer")
class NepSimulatorBeheer {

    @POST
    @Path("/legen")
    fun legen(): Response = geweigerd()

    @POST
    @Path("/seed")
    fun seed(): Response = geweigerd()

    @GET
    @Path("/magazijnen")
    fun magazijnen(): Response = geweigerd()

    private fun geweigerd(): Response = Response.status(Response.Status.UNAUTHORIZED)
        .type("application/problem+json")
        .entity(
            mapOf(
                "type" to "about:blank",
                "title" to "Unauthorized",
                "status" to Response.Status.UNAUTHORIZED.statusCode,
                "detail" to "Het beheerpad vereist een geldige X-Beheer-Token-header",
            ),
        )
        .build()
}
