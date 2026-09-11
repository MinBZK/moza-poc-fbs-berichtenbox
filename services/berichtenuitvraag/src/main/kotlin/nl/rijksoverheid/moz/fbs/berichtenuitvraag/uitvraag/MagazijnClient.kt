package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
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
 * `QuarkusRestClientBuilder`. Daarom géén `@RegisterRestClient`: de URL hoort niet
 * via `quarkus.rest-client.*.url`-config te komen maar uit het
 * magazijnregister (`magazijnen."<OIN>".url`).
 *
 * TODO(#552): vervangen door FSC outway zodra de federatieve connectiviteit
 * op MOZ-niveau is vastgesteld.
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

    /**
     * Haalt de berichttekst op bij het openen van een bericht; de sessiecache draagt hem niet,
     * dus dit is het enige pad waarlangs de uitvraag eraan komt.
     *
     * Een eigen, smalle DTO als returntype: uitvraags eigen `Bericht`-model hergebruiken kan
     * niet, want het magazijn modelleert `status` als object waar uitvraag een enum-string
     * gebruikt en Jackson die twee vormen niet op één type deserialiseert (dezelfde botsing
     * als bij [patchBericht]). De overige velden komen uit de sessiecache.
     */
    @GET
    @Path("/{berichtId}")
    fun bericht(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @PathParam("berichtId") berichtId: UUID,
    ): MagazijnBerichtInhoud

    @GET
    @Path("/{berichtId}/bijlagen/{bijlageId}")
    fun bijlage(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @PathParam("berichtId") berichtId: UUID,
        @PathParam("bijlageId") bijlageId: UUID,
    ): Response
}

/** Het deel van het magazijn-detailantwoord dat de uitvraag nodig heeft: de berichttekst. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MagazijnBerichtInhoud(
    @param:JsonProperty("inhoud") val inhoud: String,
)
