package nl.rijksoverheid.moz.fbs.magazijnsimulator.berichten

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.PROBLEM_JSON
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.onverwachteFoutProblem
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Request-property waarmee de resource het MIME-type van een bijlage doorgeeft aan
 * [BijlageContentTypeFilter]. Een eigen namespace, zodat geen ander endpoint hem per ongeluk zet.
 */
internal const val BIJLAGE_MIME_TYPE_PROPERTY = "fbs.simulator.bijlage.mimeType"

/**
 * Request-property met de naam van de bijlage, voor de `filename`-parameters in de
 * `Content-Disposition`. Optioneel: zonder naam gaat de response uit met alleen de dispositie. De
 * waarde gaat onbewerkt mee — saneren en coderen gebeurt in [BijlageContentDisposition].
 */
internal const val BIJLAGE_NAAM_PROPERTY = "fbs.simulator.bijlage.naam"

/**
 * Zet de `Content-Type` van een bijlage-download op het opgeslagen MIME-type, en de bijbehorende
 * `Content-Disposition`: `attachment` zodat een browser een aangeleverde `text/html`- of
 * `image/svg+xml`-bijlage nooit inline rendert en onder onze origin laat draaien, `inline` voor de
 * typen waarvoor dat pad niet bestaat. Die afweging staat in [BijlageContentDisposition] en volgt
 * het echte magazijn.
 *
 * De spec laat dit endpoint elk mediatype produceren, dus het type is niet vooraf bekend en moet
 * uit de response komen. Een `@NameBinding` om dit filter tot dat ene endpoint te beperken werkt niet:
 * Quarkus REST neemt zo'n annotatie op een override-methode niet over uit een gegenereerde
 * interface. Het filter doet daarom niets zolang de property afwezig is, en dat maakt globaal
 * draaien veilig.
 *
 * Het MIME-type wordt hier opnieuw geparsed, ook al deed de resource dat al: een waarde die
 * ongeparseerd in een header belandt, laat header-splitting via `\r\n` toe. Dat parsen weigert ook
 * een waarde mét control-tekens: die parseert wél, maar laat de HTTP-laag pas bij het schrijven van
 * de response klappen. Bij een onbruikbare waarde gaan de bytes niet de deur uit maar volgt een 500
 * met correlatie-id — een bijlage met een `Content-Type` dat niet klopt is erger dan geen bijlage.
 */
@Provider
class BijlageContentTypeFilter : ContainerResponseFilter {

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        val mimeType = requestContext.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) as? String ?: return

        val geparsed = bijlageMediaType(mimeType)

        if (geparsed == null) {
            val foutId = UUID.randomUUID()

            log.errorf("Ongeldig MIME-type op de request-property (foutId=%s)", foutId)

            // Niet doorlaten met de standaard-`Content-Type`: dan gaan de bytes alsnog de deur uit,
            // met een type dat niet klopt. De resource heeft de waarde al geparsed, dus hier komen
            // betekent dat er iets is dat we niet begrijpen — en dan is 500 het eerlijke antwoord.
            responseContext.status = Response.Status.INTERNAL_SERVER_ERROR.statusCode
            responseContext.entity = onverwachteFoutProblem(foutId)
            responseContext.headers.putSingle("Content-Type", PROBLEM_JSON.toString())

            return
        }

        val naam = requestContext.getProperty(BIJLAGE_NAAM_PROPERTY) as? String

        responseContext.headers.putSingle("Content-Type", geparsed.toString())
        responseContext.headers.putSingle("Content-Disposition", BijlageContentDisposition.waarde(geparsed, naam))
    }

    private companion object {
        private val log: Logger = Logger.getLogger(BijlageContentTypeFilter::class.java)
    }
}
