package nl.rijksoverheid.moz.fbs.beheerconsole.stelsel

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/** Het publieke stelseldocument van het stelselregister; het adres staat in `quarkus.rest-client.stelselregister.url`. */
@RegisterRestClient(configKey = "stelselregister")
@Path("/api/v1")
interface StelselregisterClient {

    @GET
    @Path("/stelseldocument")
    @Produces("application/jose")
    fun stelseldocument(): String
}
