package nl.rijksoverheid.moz.berichtenlijst.magazijn

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.berichtenlijst.berichten.Bericht

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
interface MagazijnClient {

    @GET
    @Path("/berichten")
    fun getBerichten(
        @QueryParam("ontvanger") ontvanger: String?,
        @QueryParam("afzender") afzender: String?,
    ): MagazijnBerichtenResponse

    @GET
    @Path("/berichten/{berichtId}")
    fun getBerichtById(@PathParam("berichtId") berichtId: String): Bericht?

    @GET
    @Path("/berichten/zoeken")
    fun zoekBerichten(
        @QueryParam("q") q: String,
        @QueryParam("ontvanger") ontvanger: String?,
        @QueryParam("afzender") afzender: String?,
    ): MagazijnBerichtenResponse
}
