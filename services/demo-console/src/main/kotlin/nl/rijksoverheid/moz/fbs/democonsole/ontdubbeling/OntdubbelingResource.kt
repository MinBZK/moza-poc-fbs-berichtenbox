package nl.rijksoverheid.moz.fbs.democonsole.ontdubbeling

import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

@Path("/api/demo/ontdubbeling")
@Produces(MediaType.APPLICATION_JSON)
class OntdubbelingResource(private val service: OntdubbelingService) {

    // Default = persona J. Pietersen; de ontvanger moet een actieve sessie hebben om het bericht te zien.
    @POST
    fun demonstreer(@QueryParam("bsn") @DefaultValue("999993653") bsn: String): OntdubbelingResultaat =
        service.demonstreer(bsn)
}
