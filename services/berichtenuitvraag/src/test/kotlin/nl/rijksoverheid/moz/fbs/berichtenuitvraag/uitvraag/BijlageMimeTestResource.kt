package nl.rijksoverheid.moz.fbs.berichtenuitvraag.uitvraag

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context

/**
 * Test-only endpoint dat [BIJLAGE_MIME_TYPE_PROPERTY] op de request-context zet, zodat
 * [BijlageContentTypeFilter] end-to-end (en dus mee-tellend voor quarkus-jacoco) geraakt
 * wordt — inclusief de fail-closed-tak. De pure unit-test ([BijlageContentTypeFilterTest])
 * dekt de logica al, maar telt niet mee voor de coverage-gate; deze @QuarkusTest-route
 * sluit dat gat zonder afhankelijk te zijn van het magazijn-client-gedrag bij een
 * onparsebaar Content-Type. Bestaat alleen in test-sources.
 */
@Path("/test-only/bijlage-mime")
@ApplicationScoped
class BijlageMimeTestResource(@param:Context private val request: ContainerRequestContext) {

    @GET
    fun get(@QueryParam("mime") mime: String): ByteArray {
        request.setProperty(BIJLAGE_MIME_TYPE_PROPERTY, mime)

        return byteArrayOf(1, 2, 3)
    }
}
