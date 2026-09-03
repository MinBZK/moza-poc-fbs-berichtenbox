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
     * Eén pagina van de berichtenlijst. `page`/`pageSize` zijn verplicht en niet optioneel: de
     * magazijn-spec laat ze weg met een default van twintig, en die default was de reden dat post
     * voorbij het eerste twintigtal nooit werd opgevraagd.
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
