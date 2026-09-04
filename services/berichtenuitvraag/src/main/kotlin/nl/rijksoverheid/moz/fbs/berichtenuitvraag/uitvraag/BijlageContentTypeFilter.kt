package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.common.bijlage.BijlageContentDisposition
import org.jboss.logging.Logger

/**
 * Request-property waarmee een resource het MIME-type voor de response-
 * Content-Type doorgeeft. Interne, unieke namespace zodat andere endpoints
 * deze property niet per ongeluk zetten.
 */
internal const val BIJLAGE_MIME_TYPE_PROPERTY = "fbs.uitvraag.bijlage.mimeType"

/**
 * Request-property met de naam van de bijlage, voor de `filename`-parameters in
 * `Content-Disposition`. Optioneel: kent de sessiecache de bijlage niet, dan gaat
 * de response uit met alleen de dispositie. De waarde gaat onbewerkt mee — saneren
 * en coderen gebeurt in [BijlageContentDisposition].
 */
internal const val BIJLAGE_NAAM_PROPERTY = "fbs.uitvraag.bijlage.naam"

/**
 * Overschrijft de `Content-Type` van een response wanneer de resource
 * expliciet een MIME-type op de request-context heeft gezet via
 * [BIJLAGE_MIME_TYPE_PROPERTY], en zet de bijbehorende `Content-Disposition`:
 * `inline` voor typen die een browser toont zonder aangeleverde code uit te
 * voeren, `attachment` voor al het overige. Die afweging staat in
 * [BijlageContentDisposition] en is gedeeld met het magazijn, zodat een
 * rechtstreekse magazijn-afname hetzelfde antwoord geeft als deze route.
 *
 * Defense-in-depth: het MIME-type wordt fail-closed gevalideerd/genormaliseerd
 * via `MediaType.valueOf`, zodat een onparsebare of door een toekomstige caller
 * ongevalideerde waarde nooit als Content-Type naar de browser passeert. De
 * val-terug `application/octet-stream` staat niet op de inline-allowlist, dus een
 * onbegrepen type gaat altijd als download de deur uit.
 *
 * Zelfde concept als `…fbs.berichtenmagazijn.ophaal.BijlageContentTypeFilter`,
 * maar bewust strenger: die variant is fail-open (ongeldig MIME → Content-Type
 * ongewijzigd), deze is fail-closed (→ octet-stream). Bij een eventuele
 * consolidatie naar fbs-common moet de fail-closed-variant leidend blijven;
 * verzwak dit gedrag niet naar fail-open.
 */
@Provider
class BijlageContentTypeFilter : ContainerResponseFilter {
    override fun filter(req: ContainerRequestContext, resp: ContainerResponseContext) {
        val mimeType = req.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) as? String ?: return
        val naam = req.getProperty(BIJLAGE_NAAM_PROPERTY) as? String

        val parsed = runCatching { MediaType.valueOf(mimeType) }.getOrNull()

        val effectief = parsed ?: MediaType.APPLICATION_OCTET_STREAM_TYPE.also {
            log.warnf("BIJLAGE_MIME_TYPE_PROPERTY ongeldig (%s); fallback naar octet-stream + attachment (fail-closed).", mimeType)
        }

        resp.headers.putSingle("Content-Type", effectief.toString())
        resp.headers.putSingle("Content-Disposition", BijlageContentDisposition.waarde(effectief, naam))
    }

    private companion object {
        private val log: Logger = Logger.getLogger(BijlageContentTypeFilter::class.java)
    }
}
