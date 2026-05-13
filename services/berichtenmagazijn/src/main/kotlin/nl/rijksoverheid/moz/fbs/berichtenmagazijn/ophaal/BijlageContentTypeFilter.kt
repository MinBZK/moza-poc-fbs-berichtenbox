package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.ext.Provider

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
 * (NameBinding was overwogen om expliciete scoping te krijgen, maar Quarkus REST
 * lijkt de annotatie op de override-methode niet over te nemen uit de gegenereerde
 * interface; property-driven gating is daardoor robuuster.)
 */
@Provider
class BijlageContentTypeFilter : ContainerResponseFilter {
    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        val mimeType = requestContext.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) as? String ?: return
        val parsed = runCatching { MediaType.valueOf(mimeType) }.getOrNull() ?: return
        responseContext.headers.putSingle("Content-Type", parsed.toString())
    }
}
