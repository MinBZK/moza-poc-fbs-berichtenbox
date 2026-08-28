package nl.rijksoverheid.moz.fbs.magazijnsimulator.aanlever

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.AanleverApi
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BerichtAanleverenRequest
import nl.rijksoverheid.moz.fbs.magazijnsimulator.api.model.BerichtResponse
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.problemResponse
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnContext

/**
 * Aanlevering bij het magazijn dat het pad-filter heeft gekozen.
 *
 * **Nog geen opslag.** Zolang de simulator niets bewaart, antwoordt aanleveren met de 503 die de
 * spec daarvoor kent. Dat is de eerlijke variant: een 201 met een vers `berichtId` zou een
 * aanlevering bevestigen die daarna nergens terug te vinden is, en dat is een fout die pas
 * stroomafwaarts opvalt. Opslag komt er in de volgende stap onder.
 */
@ApplicationScoped
class SimulatorAanleverResource(private val magazijnContext: MagazijnContext) : AanleverApi {

    override fun leverBerichtAan(berichtAanleverenRequest: BerichtAanleverenRequest): BerichtResponse =
        throw WebApplicationException(
            problemResponse(
                status = Response.Status.SERVICE_UNAVAILABLE.statusCode,
                title = "Service Unavailable",
                detail = "Magazijn ${magazijnContext.magazijn.oin} kan nog geen berichten opslaan",
            ),
        )
}
