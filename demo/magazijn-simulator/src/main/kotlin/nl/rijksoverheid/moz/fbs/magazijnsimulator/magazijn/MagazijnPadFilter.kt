package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.PreMatching
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.problemResponse

/**
 * Kiest het magazijn op het pad-prefix `/magazijn/<OIN>` en haalt dat prefix weg vóór het matchen,
 * zodat de gegenereerde resources — die de paden van de gedeelde spec dragen — ongewijzigd blijven
 * werken. De vorm van het prefix, en het terugzetten ervan in de HAL-links, staat in [MagazijnPad].
 *
 * Bewust geen terugval op een default-magazijn: een onbekende OIN hoort een 404 te zijn, zodat een
 * verkeerd geconfigureerd register luidruchtig faalt in plaats van stil bij het eerste magazijn uit
 * te komen.
 *
 * Volgorde van de twee controles: eerst de vórm van het pad, dan of het magazijn bestaat. Zo krijgt
 * een aanroeper die het pad verkeerd opbouwt te horen hoe het wél moet, en een aanroeper met een
 * correct pad te horen welke OIN niet bestaat — in plaats van twee keer dezelfde vage melding.
 */
@Provider
@PreMatching
class MagazijnPadFilter(
    private val magazijnen: GesimuleerdeMagazijnen,
    private val context: MagazijnContext,
) : ContainerRequestFilter {

    override fun filter(requestContext: ContainerRequestContext) {
        val pad = requestContext.uriInfo.path
        val oin = MagazijnPad.oinUit(pad)

        if (oin == null) {
            requestContext.abortWith(geenMagazijnPad(pad))

            return
        }

        val magazijn = magazijnen.voorOin(oin)

        if (magazijn == null) {
            requestContext.abortWith(onbekendMagazijn(oin))

            return
        }

        context.magazijn = magazijn
        requestContext.setRequestUri(
            requestContext.uriInfo.requestUriBuilder
                .replacePath(MagazijnPad.padNaPrefix(pad, oin))
                .build(),
        )
    }

    private fun geenMagazijnPad(pad: String): Response = problemResponse(
        status = Response.Status.NOT_FOUND.statusCode,
        title = "Not Found",
        detail = "Pad hoort de vorm ${MagazijnPad.VORM} te hebben; ontvangen: $pad",
    )

    private fun onbekendMagazijn(oin: String): Response = problemResponse(
        status = Response.Status.NOT_FOUND.statusCode,
        title = "Not Found",
        // De OIN mag voluit in het antwoord: het is een publieke organisatie-identificator en
        // precies de waarde die de aanroeper nodig heeft om zijn register na te lopen.
        detail = "Geen gesimuleerd magazijn met OIN $oin",
    )
}
