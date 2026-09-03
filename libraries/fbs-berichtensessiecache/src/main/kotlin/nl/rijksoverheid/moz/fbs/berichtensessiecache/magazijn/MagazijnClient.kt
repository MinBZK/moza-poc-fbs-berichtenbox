package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
internal interface MagazijnClient {

    /**
     * Eén pagina van de berichtenlijst. `page`/`pageSize` worden expliciet meegestuurd: laat je ze
     * weg, dan kiest het magazijn zijn eigen default (twintig) en blijft alles daarna onopgevraagd.
     * De aanroeper pagineert door tot het magazijn leeg is of de cap bereikt is.
     */
    @GET
    @Path("/berichten")
    fun getBerichten(
        @HeaderParam("X-Ontvanger") ontvanger: String?,
        @QueryParam("afzender") afzender: String?,
        @QueryParam("page") page: Int,
        @QueryParam("pageSize") pageSize: Int,
    ): MagazijnBerichtenResponse

    @GET
    @Path("/berichten/{berichtId}")
    fun getBerichtById(@PathParam("berichtId") berichtId: String): MagazijnBericht?
}
