package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

/**
 * Request-property waarmee een resource het MIME-type voor de response-Content-Type
 * doorgeeft aan [BijlageContentTypeFilter]. Een interne, unieke namespace zodat
 * andere endpoints deze property niet per ongeluk zetten.
 */
internal const val BIJLAGE_MIME_TYPE_PROPERTY = "fbs.bijlage.mimeType"

/**
 * Overschrijft de `Content-Type` van een response wanneer de resource expliciet
 * een MIME-type op de request-context heeft gezet via [BIJLAGE_MIME_TYPE_PROPERTY].
 * De filter doet niets als de property afwezig is, dus hij is veilig om globaal
 * te draaien; alleen `OphaalResource.getBijlage` zet de property.
 *
 * NameBinding was overwogen voor expliciete scoping, maar Quarkus REST neemt de
 * annotatie op de override-methode niet over uit de gegenereerde interface;
 * property-driven gating is daardoor robuuster (zie CLAUDE.md "Quarkus configuratie").
 */
@Provider
class BijlageContentTypeFilter : ContainerResponseFilter {
    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        val mimeType = requestContext.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) as? String ?: return
        val parsed = runCatching { MediaType.valueOf(mimeType) }.getOrNull()
        if (parsed == null) {
            // Een ongeldig MIME-type in de DB duidt op een aanlever-bug of corruptie.
            // We loggen het zodat het zichtbaar wordt; de fallback `application/octet-stream`
            // van RESTEasy is een veilige default (geen XSS, browser biedt download aan).
            log.warnf("Ongeldig MIME-type in bijlage-metadata; val terug op default. mimeType=%s", mimeType)
            return
        }
        responseContext.headers.putSingle("Content-Type", parsed.toString())
    }

    private companion object {
        private val log: Logger = Logger.getLogger(BijlageContentTypeFilter::class.java)
    }
}
