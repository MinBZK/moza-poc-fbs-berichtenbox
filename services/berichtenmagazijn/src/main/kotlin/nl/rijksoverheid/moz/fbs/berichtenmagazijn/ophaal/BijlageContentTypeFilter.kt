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
 * een MIME-type op de request-context heeft gezet via [BIJLAGE_MIME_TYPE_PROPERTY],
 * en forceert `Content-Disposition: attachment` om inline-rendering door de
 * browser uit te sluiten. Dat dicht een stored-XSS-vector: een aangeleverde
 * bijlage met `text/html` (of `image/svg+xml`) zou anders bij download in een
 * browser kunnen draaien onder onze origin. CSP `frame-ancestors 'none'` dekt
 * alleen iframes — top-level navigatie naar het download-endpoint niet.
 *
 * Het filter doet niets als de property afwezig is, dus het is veilig om globaal
 * te draaien; alleen `OphaalResource.getBijlage` zet de property.
 *
 * Defense-in-depth: parse het MIME-type opnieuw via [MediaType.valueOf]. Een
 * toekomstige caller (test, ander endpoint) zou de property zonder
 * pre-validatie kunnen zetten; ongeparste waarde zou `\r\n`-header-splitting
 * toelaten. Bij een ongeldige waarde laten we de default `Content-Type` staan.
 *
 * NameBinding is overwogen voor expliciete scoping, maar Quarkus REST neemt de
 * annotatie op de override-methode niet over uit de gegenereerde interface;
 * property-driven gating is daardoor robuuster (zie CLAUDE.md "Quarkus configuratie").
 */
@Provider
class BijlageContentTypeFilter : ContainerResponseFilter {
    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        val mimeType = requestContext.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) as? String ?: return
        val parsed = runCatching { MediaType.valueOf(mimeType) }.getOrNull()
        if (parsed == null) {
            log.warnf(
                "BIJLAGE_MIME_TYPE_PROPERTY bevat een ongeldige MediaType (%s); Content-Type ongewijzigd gelaten. " +
                    "De resource zou dit horen te valideren — check de caller.",
                mimeType,
            )
            return
        }
        responseContext.headers.putSingle("Content-Type", parsed.toString())
        // Geen filename: de naam van de bijlage staat al in BijlageMetadata van
        // de detail-call. Filename in Content-Disposition vereist sanitatie/
        // RFC 5987-encoding; weglaten is veiliger en functioneel afdoende.
        responseContext.headers.putSingle("Content-Disposition", "attachment")
    }

    private companion object {
        private val log: Logger = Logger.getLogger(BijlageContentTypeFilter::class.java)
    }
}
