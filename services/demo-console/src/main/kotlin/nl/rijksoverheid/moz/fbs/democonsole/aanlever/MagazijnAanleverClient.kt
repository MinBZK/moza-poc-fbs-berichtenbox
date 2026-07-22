package nl.rijksoverheid.moz.fbs.democonsole.aanlever

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.democonsole.generator.AanleverVerzoek

/**
 * Minimale client voor de magazijn-aanlever-API. De base-URI wordt per magazijn
 * programmatisch gezet (zie AanleverService), zodat de console met een variabel aantal
 * magazijnen overweg kan (fase 6) zonder per magazijn een config-key.
 */
@Path("/api/v1/berichten")
interface MagazijnAanleverClient {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    fun leverAan(verzoek: AanleverVerzoek): Response
}
