package nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.PreMatching
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.problemResponse
import org.jboss.logging.Logger
import java.net.URI
import java.net.URISyntaxException

/**
 * Kiest het magazijn op het pad-prefix `/magazijn/<OIN>` en haalt dat prefix weg vóór het matchen,
 * zodat de gegenereerde resources — die de paden van de gedeelde spec dragen — ongewijzigd blijven
 * werken. De vorm van het prefix, en het terugzetten ervan in de HAL-links, staat in [MagazijnPad].
 *
 * Bewust geen terugval op een default-magazijn: een onbekende OIN hoort een 404 te zijn, zodat een
 * verkeerd geconfigureerd register luidruchtig faalt in plaats van stil bij het eerste magazijn uit
 * te komen.
 *
 * Volgorde van de controles: eerst de vórm van het pad, dan of het magazijn bestaat. Zo krijgt een
 * aanroeper die het pad verkeerd opbouwt te horen hoe het wél moet, en een aanroeper met een correct
 * pad te horen welke OIN niet bestaat — in plaats van twee keer dezelfde vage melding.
 */
@Provider
@PreMatching
class MagazijnPadFilter(
    private val magazijnen: GesimuleerdeMagazijnen,
    private val context: MagazijnContext,
) : ContainerRequestFilter {

    private val log = Logger.getLogger(MagazijnPadFilter::class.java)

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

        val herschreven = herschrijf(requestContext, oin)

        if (herschreven == null) {
            requestContext.abortWith(geenMagazijnPad(pad))

            return
        }

        context.magazijn = magazijn
        requestContext.setRequestUri(herschreven)
    }

    /**
     * De request-URI met het prefix eraf, of `null` als er geen bruikbare URI van te maken is.
     *
     * Dat laatste is geen theoretisch geval en het is de reden dat dit in een `try` staat: een pad
     * met accolades erin — `/berichten/{id}` — is door elke client te sturen, en Quarkus REST bouwt
     * `UriInfo.requestUri` met een `UriBuilder` die accolades als URI-template leest. Het opvragen
     * van die URI gooit dan al, nog vóór wij eraan rekenen. Een echt magazijn geeft op zo'n pad een
     * 404 omdat `{id}` geen UUID is; hier hoort hetzelfde uit te komen, niet een 500 die de demo-log
     * volschrijft met "onverwachte fout".
     *
     * De herkenning hierboven werkt op het gedecodeerde pad (Quarkus REST biedt geen onbewerkte
     * variant: `getPath(false)` gooit), het herschrijven op het onbewerkte. Voor het prefix zelf
     * maakt dat niets uit — `/magazijn/` en `/api/v1/` bevatten geen tekens die gecodeerd worden —
     * en waar het wél uiteenloopt, valt het de veilige kant op: dan knipt [MagazijnPad.padNaPrefix]
     * niets weg, blijft het prefix in het pad staan en matcht geen enkele resource. Dus 404, nooit
     * een ánder magazijn.
     */
    private fun herschrijf(requestContext: ContainerRequestContext, oin: String): URI? = try {
        MagazijnPad.zonderPrefix(requestContext.uriInfo.requestUri, oin)
    } catch (ex: IllegalArgumentException) {
        // Op debug en niet hoger: dit is invoer van een aanroeper, geen storing. Wél loggen, zodat
        // een 404 die iemand niet verwacht na te zoeken is.
        log.debugf(ex, "Pad niet te herschrijven tot een geldige URI")

        null
    } catch (ex: URISyntaxException) {
        log.debugf(ex, "Pad niet te herschrijven tot een geldige URI")

        null
    }

    private fun geenMagazijnPad(pad: String): Response = problemResponse(
        status = Response.Status.NOT_FOUND.statusCode,
        title = "Not Found",
        // Het pad wordt afgekapt teruggegeven: het is invoer van de aanroeper, en een antwoord hoort
        // niet mee te groeien met wat iemand erin stopt.
        detail = "Pad hoort de vorm ${MagazijnPad.VORM} te hebben; ontvangen: ${pad.take(MAX_PAD_IN_MELDING)}",
    )

    private fun onbekendMagazijn(oin: String): Response = problemResponse(
        status = Response.Status.NOT_FOUND.statusCode,
        title = "Not Found",
        // De OIN mag voluit in het antwoord: het is een publieke organisatie-identificator en
        // precies de waarde die de aanroeper nodig heeft om zijn register na te lopen.
        detail = "Geen gesimuleerd magazijn met OIN $oin",
    )

    private companion object {
        const val MAX_PAD_IN_MELDING = 200
    }
}
