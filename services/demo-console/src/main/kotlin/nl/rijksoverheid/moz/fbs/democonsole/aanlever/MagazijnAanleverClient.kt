package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverVerzoek

/** Bericht-ID dat het magazijn toekent (uit de 201-respons), nodig voor de status-PATCH. */
data class AanleverRespons(val berichtId: String)

/** Magazijn-status-patch: `{gelezen: true}` (boolean — het magazijn, niet de uitvraag-enum). */
data class StatusPatch(val gelezen: Boolean)

/**
 * Minimale client voor de magazijn-API. De base-URI wordt per magazijn programmatisch gezet
 * (zie AanleverService), zodat de console met een variabel aantal magazijnen overweg kan.
 */
@Path("/api/v1/berichten")
interface MagazijnAanleverClient {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    fun leverAan(verzoek: AanleverVerzoek): Response

    // Zet de leesstatus rechtstreeks op het magazijn (geen sessiecache nodig). X-Ontvanger is
    // TYPE:WAARDE; body is merge-patch met een boolean `gelezen`.
    @PATCH
    @Path("/{berichtId}")
    @Consumes("application/merge-patch+json")
    fun markeer(
        @PathParam("berichtId") berichtId: String,
        @HeaderParam("X-Ontvanger") ontvanger: String,
        patch: StatusPatch,
    ): Response
}
