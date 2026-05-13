package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import jakarta.ws.rs.NameBinding
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.ext.Provider

internal const val BIJLAGE_MIME_TYPE_PROPERTY = "fbs.bijlage.mimeType"

/**
 * NameBinding zodat [BijlageContentTypeFilter] alleen op resource-methodes
 * draait die dit expliciet vragen. Voorkomt dat een (toekomstige) verkeerde
 * herbruik van de property-naam een Content-Type op een andere endpoint kaapt.
 */
@NameBinding
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class OverrideContentType

/**
 * Overschrijft de `Content-Type` van een response met het MIME-type dat de
 * resource via [BIJLAGE_MIME_TYPE_PROPERTY] op de request-context heeft gezet.
 * De filter draait alleen op endpoints geannoteerd met [OverrideContentType].
 */
@Provider
@OverrideContentType
class BijlageContentTypeFilter : ContainerResponseFilter {
    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        val mimeType = requestContext.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) as? String ?: return
        val parsed = runCatching { MediaType.valueOf(mimeType) }.getOrNull() ?: return
        responseContext.headers.putSingle("Content-Type", parsed.toString())
    }
}
