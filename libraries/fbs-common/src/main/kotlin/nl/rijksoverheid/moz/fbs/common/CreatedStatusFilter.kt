package nl.rijksoverheid.moz.fbs.common

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * Zet de HTTP-status naar 201 Created voor succesvolle POST-requests.
 *
 * De OpenAPI generator (jaxrs-spec, returnResponse=false) genereert methoden
 * die het concrete response-type retourneren, waardoor de status code niet
 * per methode instelbaar is via het return-type. Deze filter corrigeert dat
 * voor POST-endpoints conform de OpenAPI spec.
 *
 * Een POST op een actiepad (laatste segment begint met `_`, zoals `_verlengen`) maakt geen
 * resource aan en houdt zijn 200: zo'n pad is een handeling op een bestaande resource.
 */
@Provider
class CreatedStatusFilter : ContainerResponseFilter {
    override fun filter(request: ContainerRequestContext, response: ContainerResponseContext) {
        if (request.method == "POST" && response.status == 200 && !isActiepad(request.uriInfo.path)) {
            response.status = 201
        }
    }

    private fun isActiepad(pad: String): Boolean = pad.substringAfterLast('/').startsWith('_')
}
