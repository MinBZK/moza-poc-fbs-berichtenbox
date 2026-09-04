package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.common.bijlage.BijlageContentDisposition
import org.jboss.logging.Logger

/**
 * Request-property waarmee een resource het MIME-type voor de response-Content-Type
 * doorgeeft aan [BijlageContentTypeFilter]. Een interne, unieke namespace zodat
 * andere endpoints deze property niet per ongeluk zetten.
 */
internal const val BIJLAGE_MIME_TYPE_PROPERTY = "fbs.bijlage.mimeType"

/**
 * Request-property met de naam van de bijlage, voor de `filename`-parameters in
 * `Content-Disposition`. Optioneel: zonder naam gaat de response uit met alleen
 * de dispositie. De waarde gaat onbewerkt mee — saneren en coderen gebeurt in
 * [BijlageContentDisposition], zodat geen enkele aanroeper dat kan overslaan.
 */
internal const val BIJLAGE_NAAM_PROPERTY = "fbs.bijlage.naam"

/**
 * Overschrijft de `Content-Type` van een response wanneer de resource expliciet
 * een MIME-type op de request-context heeft gezet via [BIJLAGE_MIME_TYPE_PROPERTY],
 * en zet de bijbehorende `Content-Disposition`: `inline` voor de typen die een
 * browser toont zonder aangeleverde code uit te voeren, `attachment` voor al het
 * overige. Die afweging staat in [BijlageContentDisposition] en is gedeeld met de
 * uitvraag, zodat een rechtstreekse afname bij dit magazijn hetzelfde antwoord
 * geeft als de route via de berichtenbox.
 *
 * Het filter doet niets als de property afwezig is, dus het is veilig om globaal
 * te draaien; alleen `BerichtenResource.getBijlage` zet de property.
 *
 * Defense-in-depth: parse het MIME-type opnieuw via [MediaType.valueOf]. Een
 * toekomstige caller (test, ander endpoint) zou de property zonder
 * pre-validatie kunnen zetten; ongeparste waarde zou `\r\n`-header-splitting
 * toelaten. Bij een ongeldige waarde laten we de default `Content-Type` staan en
 * bieden we de bytes als download aan: een type dat we niet begrijpen, tonen we niet.
 *
 * NameBinding is overwogen voor expliciete scoping, maar Quarkus REST neemt de
 * annotatie op de override-methode niet over uit de gegenereerde interface;
 * property-driven gating is daardoor robuuster.
 */
@Provider
class BijlageContentTypeFilter : ContainerResponseFilter {
    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        val mimeType = requestContext.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) as? String ?: return
        val naam = requestContext.getProperty(BIJLAGE_NAAM_PROPERTY) as? String
        val parsed = runCatching { MediaType.valueOf(mimeType) }.getOrNull()

        if (parsed == null) {
            log.warnf(
                "BIJLAGE_MIME_TYPE_PROPERTY bevat een ongeldige MediaType (%s); Content-Type ongewijzigd gelaten. " +
                    "De resource zou dit horen te valideren — check de caller.",
                mimeType,
            )
            responseContext.headers.putSingle("Content-Disposition", "attachment")

            return
        }

        responseContext.headers.putSingle("Content-Type", parsed.toString())
        responseContext.headers.putSingle("Content-Disposition", BijlageContentDisposition.waarde(parsed, naam))
    }

    private companion object {
        private val log: Logger = Logger.getLogger(BijlageContentTypeFilter::class.java)
    }
}
