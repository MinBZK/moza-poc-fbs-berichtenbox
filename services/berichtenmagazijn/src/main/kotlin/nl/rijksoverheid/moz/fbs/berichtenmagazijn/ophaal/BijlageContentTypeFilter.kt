package nl.rijksoverheid.moz.fbs.berichtenmagazijn.ophaal

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
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
 * Het filter doet niets als de property afwezig is, dus het is veilig om globaal
 * te draaien; alleen `OphaalResource.getBijlage` zet de property.
 *
 * De resource valideert het MIME-type vóór het zetten van de property: een
 * ongeldige waarde levert een 500 op (geen bytes onder een verkeerd content-type).
 * Deze filter mag er daarom van uitgaan dat de waarde een geldig MediaType is.
 *
 * NameBinding is overwogen voor expliciete scoping, maar Quarkus REST neemt de
 * annotatie op de override-methode niet over uit de gegenereerde interface;
 * property-driven gating is daardoor robuuster (zie CLAUDE.md "Quarkus configuratie").
 */
@Provider
class BijlageContentTypeFilter : ContainerResponseFilter {
    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        val mimeType = requestContext.getProperty(BIJLAGE_MIME_TYPE_PROPERTY) as? String ?: return
        responseContext.headers.putSingle("Content-Type", mimeType)
    }
}
