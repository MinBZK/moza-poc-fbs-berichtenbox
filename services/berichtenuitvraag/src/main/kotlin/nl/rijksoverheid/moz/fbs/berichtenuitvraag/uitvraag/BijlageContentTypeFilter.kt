package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

/**
 * Request-property waarmee een resource het MIME-type voor de response-
 * Content-Type doorgeeft. Interne, unieke namespace zodat andere endpoints
 * deze property niet per ongeluk zetten.
 */
internal const val BIJLAGE_MIME_TYPE_PROPERTY = "fbs.uitvraag.bijlage.mimeType"

/**
 * Overschrijft de `Content-Type` van een response wanneer de resource
 * expliciet een MIME-type op de request-context heeft gezet via
 * [BIJLAGE_MIME_TYPE_PROPERTY], en forceert `Content-Disposition: attachment`
 * om inline-rendering door de browser uit te sluiten (stored-XSS-bescherming).
 * Defense-in-depth: parse het MIME-type opnieuw zodat een toekomstige caller
 * zonder pre-validatie geen header-splitting kan introduceren.
 *
 * Identiek patroon aan `…fbs.berichtenmagazijn.ophaal.BijlageContentTypeFilter` —
 * pas bij een derde gebruiker verplaatsen naar fbs-common.
 */
@Provider
class BijlageContentTypeFilter : ContainerResponseFilter {
    override fun filter(req: ContainerRequestContext, resp: ContainerResponseContext) {
        val mimeType = req.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) as? String ?: return

        val parsed = runCatching { MediaType.valueOf(mimeType) }.getOrNull()
        if (parsed == null) {
            log.warnf("BIJLAGE_MIME_TYPE_PROPERTY ongeldig (%s); Content-Type ongewijzigd.", mimeType)
            return
        }

        resp.headers.putSingle("Content-Type", parsed.toString())
        resp.headers.putSingle("Content-Disposition", "attachment")
    }

    private companion object {
        private val log: Logger = Logger.getLogger(BijlageContentTypeFilter::class.java)
    }
}
