package nl.rijksoverheid.moz.fbs.magazijnsimulator.beheer

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.problemResponse
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnPad

/**
 * Laat het beheerpad alleen door met het juiste token in de `X-Beheer-Token`-header.
 *
 * Is er geen token geconfigureerd, dan staat het pad open — en dat kan alleen onder dev en test,
 * want [BeheerToken] blokkeert daarbuiten de boot.
 */
@Provider
class BeheerTokenFilter(private val token: BeheerToken) : ContainerRequestFilter {

    override fun filter(requestContext: ContainerRequestContext) {
        if (!MagazijnPad.isBeheerPad(requestContext.uriInfo.path)) return

        if (!token.laatDoor(requestContext.getHeaderString(HEADER))) {
            requestContext.abortWith(
                problemResponse(
                    status = Response.Status.UNAUTHORIZED.statusCode,
                    title = "Unauthorized",
                    // Niet verklappen wát er mis is: een melding die onderscheid maakt tussen
                    // "ontbreekt" en "klopt niet", vertelt een aanroeper dat hij op de goede weg is.
                    detail = "Het beheerpad vereist een geldige $HEADER-header",
                ),
            )
        }
    }

    private companion object {
        const val HEADER = "X-Beheer-Token"
    }
}
