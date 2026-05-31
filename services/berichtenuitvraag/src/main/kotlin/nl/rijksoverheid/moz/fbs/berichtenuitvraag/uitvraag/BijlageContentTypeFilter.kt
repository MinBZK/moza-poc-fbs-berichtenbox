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
 * Defense-in-depth: het MIME-type wordt fail-closed gevalideerd/genormaliseerd
 * via `MediaType.valueOf`, zodat een onparsebare of door een toekomstige caller
 * ongevalideerde waarde nooit als Content-Type naar de browser passeert.
 *
 * Zelfde concept als `…fbs.berichtenmagazijn.ophaal.BijlageContentTypeFilter`,
 * maar bewust strenger: die variant is fail-open (ongeldig MIME → Content-Type
 * ongewijzigd), deze is fail-closed (→ octet-stream + attachment). Bij een
 * eventuele consolidatie naar fbs-common moet de fail-closed-variant leidend
 * blijven; verzwak dit gedrag niet naar fail-open.
 */
@Provider
class BijlageContentTypeFilter : ContainerResponseFilter {
    override fun filter(req: ContainerRequestContext, resp: ContainerResponseContext) {
        val mimeType = req.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) as? String ?: return

        val parsed = runCatching { MediaType.valueOf(mimeType) }.getOrNull()

        // Fail-closed: een onparsebaar MIME-type duidt op een upstream-fout of
        // een poging tot header-splitting. Geen passthrough naar de browser
        // (zou stored-XSS-bescherming ondergraven); fallback op
        // `application/octet-stream` met `Content-Disposition: attachment` zodat
        // de browser het ALTIJD als download behandelt.
        val effectief = parsed ?: MediaType.APPLICATION_OCTET_STREAM_TYPE.also {
            log.warnf("BIJLAGE_MIME_TYPE_PROPERTY ongeldig (%s); fallback naar application/octet-stream.", mimeType)
        }

        resp.headers.putSingle("Content-Type", effectief.toString())
        resp.headers.putSingle("Content-Disposition", "attachment")
    }

    private companion object {
        private val log: Logger = Logger.getLogger(BijlageContentTypeFilter::class.java)
    }
}
