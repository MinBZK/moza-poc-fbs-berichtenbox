package nl.rijksoverheid.moz.fbs.magazijnsimulator

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnPad

/**
 * Zet de status van een geslaagde `POST` op 201, zoals de spec voor `/aanleveringen` voorschrijft.
 *
 * De generator maakt methodes die het antwoordtype teruggeven (`returnResponse=false`), en dan is de
 * statuscode niet per methode te kiezen — Quarkus zou 200 sturen. Het echte magazijn lost dat op
 * dezelfde manier op; wijkt de simulator hier af, dan is het verschil met één blik op de statuscode
 * te zien.
 *
 * Het beheerpad blijft erbuiten: dat volgt de spec niet, en `seed` en `legen` maken geen resource
 * aan waar een 201 naar zou verwijzen.
 */
@Provider
class CreatedStatusFilter : ContainerResponseFilter {

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        if (MagazijnPad.isBeheerPad(requestContext.uriInfo.path)) return

        if (requestContext.method == "POST" && responseContext.status == HTTP_OK) {
            responseContext.status = HTTP_CREATED
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
    }
}
