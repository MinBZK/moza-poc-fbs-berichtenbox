package nl.rijksoverheid.moz.fbs.democonsole.storing

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/** Bericht voor een incoming webhook: `{"text":"…","username":"FBS Demo"}`. */
data class MattermostBericht(val text: String, val username: String)

/** Incoming webhook van Mattermost; de webhook-url is het volledige adres, er komt geen pad bij. */
interface MattermostWebhook {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    fun post(bericht: MattermostBericht): Response
}
