package nl.rijksoverheid.moz.fbs.magazijnsimulator.gedrag

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import nl.rijksoverheid.moz.fbs.magazijnsimulator.fout.problemResponse
import nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnContext
import org.jboss.logging.Logger

/**
 * Laat een magazijn zich gedragen zoals het is ingesteld: traag, haperend, kapot, onbereikbaar,
 * weigerend of antwoordend met onzin.
 *
 * **Op élke endpoint**, dus ook op het markeren als gelezen, het verplaatsen naar een map en het
 * aanleveren van een bericht. Dat is realistisch — in het echte stelsel is een schrijfactie net zo
 * goed een aanroep naar een andere organisatie — maar het heeft een gevolg dat niet mag verrassen:
 * een magazijn dat op storing staat weigert ook nieuwe berichten. Vullen doe je dus vóór de storing,
 * of via het beheerpad; waarom dat buiten de simulatie valt, staat bij
 * [nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnPad.BEHEER_SEGMENT].
 *
 * **Bewust geen `@PreMatching`.** Dit filter wacht, en wachten hoort op een worker-thread. Een
 * `@PreMatching`-filter draait vóórdat Quarkus weet welke resource geraakt wordt en dus vóór de
 * overstap naar die thread; een `Thread.sleep` zou daar de event-loop blokkeren en álle magazijnen
 * tegelijk stilzetten. Na het matchen draait dit filter op dezelfde thread als de resource.
 *
 * Die garantie hangt eraan dat élke resource-methode een gewoon antwoordtype teruggeeft: Quarkus
 * beslist per methode of hij blocking draait. Komt er ooit een endpoint bij dat een `Uni` of `Multi`
 * teruggeeft, dan draait dit filter voor dát endpoint wél op de event-loop, en dan hoort de
 * vertraging daar op een andere manier te landen.
 *
 * Wachten kost een worker-thread en geen database-connection; hoeveel threads er zijn, staat met de
 * rekensom erbij in `application.properties` (`quarkus.thread-pool.max-threads`).
 *
 * Het magazijn is op dit punt al gekozen door
 * [nl.rijksoverheid.moz.fbs.magazijnsimulator.magazijn.MagazijnPadFilter]; een verzoek dat daar is
 * afgebroken komt hier niet langs.
 */
@Provider
class GedragFilter(
    private val context: MagazijnContext,
    private val uitvoering: GedragUitvoering,
) : ContainerRequestFilter {

    private val log = Logger.getLogger(GedragFilter::class.java)

    override fun filter(requestContext: ContainerRequestContext) {
        // Geen magazijn gekozen betekent: dit is het beheerpad, dat buiten de simulatie valt. Het
        // pad-filter is de enige die kiest, en het breekt elk ander pad af — een verzoek zonder
        // magazijn dat hier komt, kan dus alleen het beheerpad zijn.
        val magazijn = context.magazijnOfNiets ?: return
        val gedrag = magazijn.gedrag

        if (gedrag.modus == GedragModus.NORMAAL && gedrag.latencyP50Ms == 0) return

        if (!wacht(uitvoering.vertragingMs(magazijn.oin, gedrag))) {
            requestContext.abortWith(afgebroken())

            return
        }

        // `UIT` rekent erop dat de aanroeper in zijn eigen timeout loopt — dat is wat de Berichtenbox
        // als "onbereikbaar" registreert, en het wachten hierboven duurt langer dan de tijd die zij
        // een magazijn gunt. Wie zónder timeout kijkt (curl, Bruno, een browser) moet daarna niet
        // alsnog een gezond antwoord met echte berichten krijgen: dan spreekt het magazijn zijn eigen
        // overzicht tegen. Het antwoord komt dus te laat én zegt dat er niets te halen viel.
        if (gedrag.modus == GedragModus.UIT) {
            meld(magazijn.oin, gedrag, gedrag.foutStatus)
            requestContext.abortWith(storing(gedrag))

            return
        }

        if (gedrag.modus == GedragModus.MALFORMED) {
            meld(magazijn.oin, gedrag, Response.Status.OK.statusCode)
            requestContext.abortWith(onbruikbaarAntwoord())

            return
        }

        if (uitvoering.valtOm(magazijn.oin, gedrag)) {
            meld(magazijn.oin, gedrag, gedrag.foutStatus)
            requestContext.abortWith(storing(gedrag))
        }
    }

    /**
     * Eén regel per gesimuleerde afbreking. Zonder die regel is een 503 van dit filter in de log
     * niet te onderscheiden van een 503 die er niet had moeten zijn — er is dan alleen de afwezigheid
     * van een foutregel, en "niets gevonden" is het omgekeerde van een bewijs.
     *
     * Op info en niet op debug: het effectieve niveau is info, dus op debug zou `zadctl logs` tijdens
     * een demo geen enkele regel opleveren en zou dat bewijs er alsnog niet zijn.
     */
    private fun meld(oin: String, gedrag: Gedrag, status: Int) {
        log.infof("Gesimuleerd gedrag %s voor magazijn %s: status %d", gedrag.modus, oin, status)
    }

    /** `false` als het wachten is onderbroken en dit verzoek dus niet meer af te maken is. */
    private fun wacht(vertragingMs: Long): Boolean {
        if (vertragingMs <= 0) return true

        return try {
            Thread.sleep(vertragingMs)

            true
        } catch (ex: InterruptedException) {
            // Iets onderbreekt deze thread — meestal een server die uitgaat, maar wat precies weten
            // we hier niet. De vlag hoort terug, en doorgaan kan niet: de resource erachter loopt met
            // een gezette interrupt-vlag de database in, waar Agroal en de driver daarop reageren met
            // een SQLException — een echte 500 met stacktrace, terwijl er niets stuk is. Het verzoek
            // hier afbreken zegt wat er aan de hand is zonder een oorzaak te verzinnen.
            Thread.currentThread().interrupt()

            log.infof(ex, "Wachten onderbroken na %d ms; verzoek afgebroken", vertragingMs)

            false
        }
    }

    /** Niet het gesimuleerde gedrag maar de simulator zelf: het wachten van dit verzoek is onderbroken. */
    private fun afgebroken(): Response = problemResponse(
        status = Response.Status.SERVICE_UNAVAILABLE.statusCode,
        title = "Service Unavailable",
        detail = "De simulator kon dit verzoek niet afmaken",
    )

    /**
     * Een antwoord dat de aanroeper wél krijgt maar niet kan gebruiken: status 200, `application/json`,
     * en een body die niet aan het schema voldoet.
     *
     * Dit is de tak die de Berichtenbox ánders behandelt dan onbereikbaarheid — hij telt niet mee
     * voor de circuit breaker — en die zonder deze modus in een demo nooit geraakt wordt, terwijl
     * juist die eerder een echte fout opleverde.
     */
    private fun onbruikbaarAntwoord(): Response = Response.ok()
        .type(MediaType.APPLICATION_JSON_TYPE)
        .entity(ONBRUIKBARE_BODY)
        .build()

    private fun storing(gedrag: Gedrag): Response = problemResponse(
        status = gedrag.foutStatus,
        title = Response.Status.fromStatusCode(gedrag.foutStatus)?.reasonPhrase ?: "Error",
        detail = "Dit magazijn is ingesteld op ${gedrag.modus.name.lowercase()}",
    )

    private companion object {
        /**
         * `berichten` hoort een lijst te zijn en de paginering-velden horen er te staan. Deze body is
         * geldige JSON en ongeldige `BerichtenLijst`, en dat is precies het geval dat we willen
         * kunnen tonen.
         */
        const val ONBRUIKBARE_BODY = """{"berichten": "dit is geen lijst"}"""
    }
}
