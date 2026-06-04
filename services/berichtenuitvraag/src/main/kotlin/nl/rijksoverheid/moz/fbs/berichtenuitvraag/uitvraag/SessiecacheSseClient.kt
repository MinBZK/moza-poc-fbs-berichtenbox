package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import io.smallrye.mutiny.Multi
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * SSE-stream-client naar de sessiecache. Aparte interface t.o.v.
 * [SessiecacheClient] zodat de Quarkus REST-client de juiste streaming-engine
 * kiest voor `Multi<String>`. URL via aparte config-key zodat beide clients
 * naar dezelfde service kunnen wijzen via `SESSIECACHE_URL`.
 */
@RegisterRestClient(configKey = "sessiecache-sse")
@Path("/api/v1/berichten/_ophalen")
interface SessiecacheSseClient {
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    fun ophalen(@HeaderParam("X-Ontvanger") xOntvanger: String): Multi<String>
}
