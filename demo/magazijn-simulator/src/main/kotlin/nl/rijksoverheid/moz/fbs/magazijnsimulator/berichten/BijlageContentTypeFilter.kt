package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

/**
 * Request-property waarmee de resource het MIME-type van een bijlage doorgeeft aan
 * [BijlageContentTypeFilter]. Een eigen namespace, zodat geen ander endpoint hem per ongeluk zet.
 */
internal const val BIJLAGE_MIME_TYPE_PROPERTY = "fbs.simulator.bijlage.mimeType"

/**
 * Zet de `Content-Type` van een bijlage-download op het opgeslagen MIME-type, en forceert
 * `Content-Disposition: attachment` zodat een browser de inhoud nooit inline rendert. Dat laatste
 * dicht een stored-XSS-pad: een aangeleverde `text/html`- of `image/svg+xml`-bijlage zou anders bij
 * het openen onder onze origin kunnen draaien.
 *
 * De spec laat dit endpoint elk mediatype produceren, dus het type is niet vooraf bekend en moet
 * uit de response komen. Een `@NameBinding` om dit filter tot dat ene endpoint te beperken werkt niet:
 * Quarkus REST neemt zo'n annotatie op een override-methode niet over uit een gegenereerde
 * interface. Het filter doet daarom niets zolang de property afwezig is, en dat maakt globaal
 * draaien veilig.
 *
 * Het MIME-type wordt hier opnieuw geparsed, ook al deed de resource dat al: een waarde die
 * ongeparseerd in een header belandt, laat header-splitting via `\r\n` toe. Bij een onbruikbare
 * waarde blijft de standaard-`Content-Type` staan.
 */
@Provider
class BijlageContentTypeFilter : ContainerResponseFilter {

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        val mimeType = requestContext.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) as? String ?: return
        val geparsed = try {
            MediaType.valueOf(mimeType)
        } catch (ex: IllegalArgumentException) {
            log.warnf(ex, "Ongeldig MIME-type op de request-property (%s); Content-Type ongewijzigd", mimeType)

            return
        }

        responseContext.headers.putSingle("Content-Type", geparsed.toString())
        // Geen filename: de naam staat al in de detail-response, en een filename in deze header
        // vraagt om RFC 5987-encoding en sanering die hier niets toevoegt.
        responseContext.headers.putSingle("Content-Disposition", "attachment")
    }

    private companion object {
        private val log: Logger = Logger.getLogger(BijlageContentTypeFilter::class.java)
    }
}
