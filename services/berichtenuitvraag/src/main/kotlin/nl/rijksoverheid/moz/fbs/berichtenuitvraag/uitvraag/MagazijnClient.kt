package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

/**
 * REST-client naar het Berichtenmagazijn. URL via
 * `quarkus.rest-client."…MagazijnClient".url` in application.properties.
 *
 * TODO(#11): vervangen door FSC outway zodra FSC-integratie er is.
 *
 * `bijlage` retourneert `Response` zodat we zowel het werkelijke
 * `Content-Type` als de bytes kunnen lezen — magazijn levert dynamic
 * Content-Type per bijlage en wij overrulen ons eigen response-header via
 * BijlageContentTypeFilter (Task 8).
 */
@RegisterRestClient
@Path("/api/v1/berichten")
interface MagazijnClient {
    /**
     * Magazijn-spec definieert PATCH-body als `application/merge-patch+json`
     * (RFC 7396). Expliciete `@Consumes` voorkomt 415 vanuit upstream.
     */
    @PATCH
    @Consumes("application/merge-patch+json")
    @Path("/{berichtId}")
    fun patchBericht(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @PathParam("berichtId") berichtId: UUID,
        patch: UitvraagDtoMapper.MagazijnPatch,
    ): Bericht

    @DELETE
    @Path("/{berichtId}")
    fun verwijderBericht(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @PathParam("berichtId") berichtId: UUID,
    )

    @GET
    @Path("/{berichtId}/bijlagen/{bijlageId}")
    fun bijlage(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @PathParam("berichtId") berichtId: UUID,
        @PathParam("bijlageId") bijlageId: UUID,
    ): Response
}
