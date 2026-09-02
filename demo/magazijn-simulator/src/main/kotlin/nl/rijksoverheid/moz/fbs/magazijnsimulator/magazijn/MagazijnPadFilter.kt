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

        // Het beheerpad hoort bij de simulator zelf en niet bij één magazijn: het is er om demo's te
        // vullen, terug te zetten en bij te sturen. Het gaat hier ongemoeid langs, en de resource
        // erachter kiest geen magazijn uit de context.
        if (MagazijnPad.isBeheerPad(pad)) return

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
            requestContext.abortWith(onbruikbaarPad())

            return
        }

        // Het beheerpad is ook via het magazijn-prefix te bereiken: `/magazijn/<OIN>/api/v1/beheer/…`
        // herschrijft naar `/beheer/…` en matcht dan de beheer-resource, mét een magazijn in de
        // context. Het token blijft afgedwongen, maar het gedrag-filter zou dan wél toeslaan — en
        // een magazijn dat op stuk staat zou langs die route niet meer te repareren zijn.
        if (MagazijnPad.isBeheerPad(herschreven.path)) {
            requestContext.abortWith(beheerpadOnderMagazijn())

            return
        }

        context.kies(magazijn)
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
     * Herkennen gebeurt op het gedecodeerde pad, herschrijven op het onbewerkte; waarom dat veilig
     * is, staat bij [MagazijnPad].
     */
    private fun herschrijf(requestContext: ContainerRequestContext, oin: String): URI? = try {
        MagazijnPad.zonderPrefix(requestContext.uriInfo.requestUri, oin)
    } catch (ex: IllegalArgumentException) {
        // Op info en niet hoger: dit is invoer van een aanroeper, geen storing. Wél zichtbaar zonder
        // debug aan te zetten, want de 404 die hieruit volgt verrast iemand die een pad stuurt dat
        // er van buiten correct uitziet.
        log.infof(ex, "Pad niet te herschrijven tot een geldige URI")

        null
    } catch (ex: URISyntaxException) {
        log.infof(ex, "Pad niet te herschrijven tot een geldige URI")

        null
    }

    /**
     * Apart van [geenMagazijnPad]: het pad hád de juiste vorm, er valt alleen geen bruikbare URI van
     * te maken. "Pad hoort de vorm … te hebben" zou hier het tegenovergestelde beweren van wat er
     * aan de hand is, en dan zoekt de aanroeper aan de verkeerde kant.
     */
    private fun onbruikbaarPad(): Response = problemResponse(
        status = Response.Status.NOT_FOUND.statusCode,
        title = "Not Found",
        detail = "Pad bevat tekens die niet in een URI kunnen voorkomen",
    )

    private fun geenMagazijnPad(pad: String): Response = problemResponse(
        status = Response.Status.NOT_FOUND.statusCode,
        title = "Not Found",
        // Het pad wordt afgekapt teruggegeven: het is invoer van de aanroeper, en een antwoord hoort
        // niet mee te groeien met wat iemand erin stopt.
        detail = "Pad hoort de vorm ${MagazijnPad.VORM} te hebben; ontvangen: ${pad.take(MAX_PAD_IN_MELDING)}",
    )

    /**
     * Apart van [geenMagazijnPad]: dit pad hád de vorm van een magazijn-pad, het is alleen de
     * verkeerde weg naar het beheerpad. "Pad hoort de vorm … te hebben" zou hier beweren dat de vorm
     * niet klopt, terwijl die exact klopte.
     */
    private fun beheerpadOnderMagazijn(): Response = problemResponse(
        status = Response.Status.NOT_FOUND.statusCode,
        title = "Not Found",
        detail = "Het beheerpad hoort niet bij een magazijn; gebruik /${MagazijnPad.BEHEER_SEGMENT}/…",
    )

    private fun onbekendMagazijn(oin: String): Response = problemResponse(
        status = Response.Status.NOT_FOUND.statusCode,
        title = "Not Found",
        // Een OIN mag voluit in het antwoord: het is een publieke organisatie-identificator en
        // precies de waarde die de aanroeper nodig heeft om zijn register na te lopen. Een segment
        // dat geen OIN-vorm heeft wordt níét geëchood — daar kan een BSN in staan, of tekst die de
        // aanroeper zelf koos, en die hoort niet in een antwoord of in de access-logs.
        detail = if (MagazijnPad.isOinVorm(oin)) {
            "Geen gesimuleerd magazijn met OIN $oin"
        } else {
            "Geen gesimuleerd magazijn op dit pad; het tweede segment hoort een OIN te zijn"
        },
    )

    private companion object {
        const val MAX_PAD_IN_MELDING = 200
    }
}
