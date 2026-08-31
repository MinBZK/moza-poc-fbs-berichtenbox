package nl.rijksoverheid.moz.fbs.democonsole.veelmagazijnen

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/** Request-matcher: het pad-prefix /mNN onderscheidt de stub (routering gaat via het pad). */
data class WireMockRequest(val method: String, val urlPath: String)

data class WireMockResponse(val status: Int)

/** Minimale WireMock-stubmapping: een 503-overlay met vaste id en hoge prioriteit (laag getal wint). */
data class WireMockStub(
    val id: String,
    val priority: Int,
    val request: WireMockRequest,
    val response: WireMockResponse,
)

/**
 * Eén mapping zoals WireMock hem teruggeeft. Alleen de id telt: daaraan herkennen we onze eigen
 * 503-overlays tussen de base-mappings. Nullable omdat we het schema van een vreemde admin-API
 * niet vastpinnen: op een niet-nullable veld zou een schemawijziging de deserialisatie laten
 * falen, terwijl een mapping zonder id per definitie niet een van de onze is.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class WireMockMapping(val id: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WireMockMappings(val mappings: List<WireMockMapping> = emptyList())

/**
 * Client voor de WireMock-admin-API van de stub-magazijnen. `mappings` levert een [Response] en
 * geen gedeserialiseerd object: de default rest-client-mapper staat uit, dus zonder de status zelf
 * te lezen komt een foutbody als een lege mappings-lijst binnen — en dat leest als "alles actief".
 * `voegOverlayToe` maakt per stub een
 * 503-mapping aan (POST met een vaste id in de body — WireMock's PUT is update-only en geeft 404 op
 * een nog-niet-bestaande id); `verwijderOverlay` haalt hem weg op die id; `herlaad` reset naar de
 * mappings van schijf (alles weer actief).
 */
@Path("/__admin/mappings")
@RegisterRestClient(configKey = "magazijnstubs")
interface WireMockAdminClient {

    @GET
    fun mappings(): Response

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    fun voegOverlayToe(stub: WireMockStub): Response

    @DELETE
    @Path("/{id}")
    fun verwijderOverlay(@PathParam("id") id: String): Response

    @POST
    @Path("/reset")
    fun herlaad(): Response
}
