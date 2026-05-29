package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * REST-client naar een Berichtenmagazijn. Instances worden niet statisch
 * geïnjecteerd — [MagazijnRouter] bouwt ze op runtime per `magazijnId` via
 * `RestClientBuilder`. Daarom géén `@RegisterRestClient`: de URL hoort niet
 * via `quarkus.rest-client.*.url`-config te komen maar via de
 * `magazijnen.urls.<id>`-map.
 *
 * TODO(#11): vervangen door FSC outway zodra FSC-integratie er is.
 *
 * `bijlage` retourneert `Response` zodat we zowel het werkelijke
 * `Content-Type` als de bytes kunnen lezen — magazijn levert dynamic
 * Content-Type per bijlage en wij overrulen ons eigen response-header via
 * [BijlageContentTypeFilter].
 *
 * `patchBericht` retourneert `Unit`: de magazijn-spec geeft een
 * `Bericht`-DTO terug waarvan het `status`-veld een gestructureerd object
 * (`BerichtStatusInfo`) is, terwijl uitvraag's `Bericht.status` een enum-
 * string is — Jackson kan dit niet deserialiseren. We hebben de body
 * sowieso niet nodig (de definitieve response naar de client komt uit
 * de sessiecache), dus laten we hem niet lezen. Status-mismatch (4xx/5xx)
 * komt nog steeds als `WebApplicationException` door de default mapper.
 */
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
    )

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
