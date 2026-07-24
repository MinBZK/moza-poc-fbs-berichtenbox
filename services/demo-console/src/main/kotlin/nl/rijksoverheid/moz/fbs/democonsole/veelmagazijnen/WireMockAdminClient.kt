package nl.rijksoverheid.moz.fbs.democonsole.veelmagazijnen

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/** WireMock header/veld-matcher, bv. `{"matches": "m07(:8080)?"}`. */
data class WireMockMatcher(val matches: String)

/** Request-matcher: vast pad plus per-stub Host-header (routering gaat via de hostnaam, niet het pad). */
data class WireMockRequest(val method: String, val urlPath: String, val headers: Map<String, WireMockMatcher>)

data class WireMockResponse(val status: Int)

/** Minimale WireMock-stubmapping: een 503-overlay met vaste id en hoge prioriteit (laag getal wint). */
data class WireMockStub(
    val id: String,
    val priority: Int,
    val request: WireMockRequest,
    val response: WireMockResponse,
)

/**
 * Client voor de WireMock-admin-API van de stub-magazijnen. `zetOverlay` plaatst per stub een
 * 503-mapping met vaste id (upsert via PUT); `verwijderOverlay` haalt hem weg; `herlaad` reset naar
 * de mappings van schijf (alles weer actief).
 */
@Path("/__admin/mappings")
@RegisterRestClient(configKey = "magazijnstubs")
interface WireMockAdminClient {

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    fun zetOverlay(@PathParam("id") id: String, stub: WireMockStub): Response

    @DELETE
    @Path("/{id}")
    fun verwijderOverlay(@PathParam("id") id: String): Response

    @POST
    @Path("/reset")
    fun herlaad(): Response
}
