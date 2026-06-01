package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Bericht
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtPatch
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.BerichtenLijst
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

/**
 * REST-client naar de Berichtensessiecache-service. URL via
 * `quarkus.rest-client."…SessiecacheClient".url` in application.properties.
 *
 * SSE-endpoint `/berichten/_ophalen` zit niet hier — dat gaat via een aparte
 * streaming-client met `Multi<String>` (zie [SessiecacheSseClient]).
 */
@RegisterRestClient
@Path("/api/v1/berichten")
interface SessiecacheClient {
    /**
     * Paginatie-queryparams heten upstream `page`/`pageSize` (niet de uitvraag-
     * spec-namen `pagina`/`paginaGrootte`). JAX-RS dropt niet-gebonden params
     * stil, dus een verkeerde naam zou de sessiecache altijd op default-pagina 0
     * laten vallen. Response-`_links` worden in [BerichtenlijstService] terug-
     * vertaald naar de uitvraag-namen.
     */
    @GET
    fun lijst(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @QueryParam("page") pagina: Int?,
        @QueryParam("pageSize") paginaGrootte: Int?,
    ): BerichtenLijst

    @GET
    @Path("/_zoeken")
    fun zoek(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @QueryParam("q") q: String,
    ): BerichtenLijst

    @GET
    @Path("/{berichtId}")
    fun bericht(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @PathParam("berichtId") berichtId: UUID,
    ): Bericht

    /**
     * Sessiecache-spec definieert PATCH-body als `application/merge-patch+json`
     * (RFC 7396). Zonder expliciete `@Consumes` zou Quarkus REST-client
     * `application/json` sturen en upstream een 415 teruggeven.
     */
    @PATCH
    @Consumes("application/merge-patch+json")
    @Path("/{berichtId}")
    fun patchBericht(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @PathParam("berichtId") berichtId: UUID,
        patch: BerichtPatch,
    ): Bericht

    @DELETE
    @Path("/{berichtId}")
    fun verwijderBericht(
        @HeaderParam("X-Ontvanger") xOntvanger: String,
        @PathParam("berichtId") berichtId: UUID,
    )
}
