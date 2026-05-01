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
 */
@Provider
class CreatedStatusFilter : ContainerResponseFilter {
    override fun filter(request: ContainerRequestContext, response: ContainerResponseContext) {
        if (request.method == "POST" && response.status == 200) {
            response.status = 201
        }
    }
}
