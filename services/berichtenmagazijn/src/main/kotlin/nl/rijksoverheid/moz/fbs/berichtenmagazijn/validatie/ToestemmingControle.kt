package nl.rijksoverheid.moz.fbs.berichtenmagazijn.validatie

import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * REST-client naar de Profiel Service. Controleert of de ontvanger toestemming
 * heeft gegeven om berichten van deze afzender te ontvangen.
 *
 * Werkt voor zowel burgers (BSN) als ondernemers (RSIN, KvK). Identificatie van
 * de ontvanger gaat via body, niet via URL: BSN's en RSIN/KvK-paren mogen
 * conform PII-richtlijnen niet in query- of pad-parameters belanden.
 */
@RegisterRestClient(configKey = "profiel-service")
@Path("/toestemmingen")
interface ToestemmingControle {

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    fun controleer(verzoek: ToestemmingVerzoek): ToestemmingAntwoord
}

data class ToestemmingVerzoek(
    val ontvangerType: String,
    val ontvangerWaarde: String,
    val afzender: String,
)

data class ToestemmingAntwoord(
    val toegestaan: Boolean,
)
