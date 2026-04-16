package nl.rijksoverheid.moz.fbs.berichtensessiecache.magazijn

import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.berichtensessiecache.berichten.Bericht

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
interface MagazijnClient {

    @GET
    @Path("/berichten")
    fun getBerichten(
        @HeaderParam("X-Ontvanger") ontvanger: String?,
        @QueryParam("afzender") afzender: String?,
    ): MagazijnBerichtenResponse

    @GET
    @Path("/berichten/{berichtId}")
    fun getBerichtById(@PathParam("berichtId") berichtId: String): Bericht?
}
