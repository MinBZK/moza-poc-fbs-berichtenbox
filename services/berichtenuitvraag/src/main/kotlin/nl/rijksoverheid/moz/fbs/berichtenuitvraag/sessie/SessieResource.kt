package nl.rijksoverheid.moz.fbs.berichtenuitvraag.sessie

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.SessiesApi
import nl.rijksoverheid.moz.fbs.berichtenuitvraag.api.model.Sessie

/**
 * REST-resource voor inlogsessies. Antwoordt met 501 zolang het gedrag niet bedraad is: dat
 * komt van de sessiecache-facade, de resource zelf houdt geen sessiestaat bij.
 */
@ApplicationScoped
class SessieResource : SessiesApi {

    override fun startSessie(xOntvanger: String): Sessie = nietGeimplementeerd()

    override fun beeindigSessie(xOntvanger: String, xSessie: String) = nietGeimplementeerd()

    private fun nietGeimplementeerd(): Nothing =
        throw WebApplicationException(Response.Status.NOT_IMPLEMENTED)
}
