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
 * of via het beheerpad, dat buiten de simulatie valt. Anders zou een kapot gezet magazijn niet meer
 * te repareren zijn.
 *
 * **Bewust geen `@PreMatching`.** Dit filter wacht, en wachten hoort op een worker-thread. Een
 * `@PreMatching`-filter draait vóórdat Quarkus weet welke resource geraakt wordt en dus vóór de
 * overstap naar die thread; een `Thread.sleep` zou daar de event-loop blokkeren en álle magazijnen
 * tegelijk stilzetten. Na het matchen draait dit filter op dezelfde thread als de resource.
 *
 * Die garantie hangt eraan dat élke resource-methode een gewoon antwoordtype teruggeeft: Quarkus
 * beslist per methode of hij blocking draait. Komt er ooit een endpoint bij dat een `Uni` of `Multi`
 * teruggeeft — een SSE-stroom ligt in deze demo voor de hand — dan draait dit filter voor dát
 * endpoint wél op de event-loop, en dan hoort de vertraging daar op een andere manier te landen.
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
        // Geen magazijn gekozen betekent: dit is het beheerpad. Dat valt buiten de simulatie, want
        // anders is een kapot gezet magazijn niet meer te repareren of te vullen.
        val magazijn = context.magazijnOfNiets ?: return
        val gedrag = magazijn.gedrag

        if (gedrag.modus == GedragModus.NORMAAL && gedrag.latencyP50Ms == 0) return

        wacht(uitvoering.vertragingMs(magazijn.oin, gedrag))

        // `UIT` heeft geen foutantwoord: het punt ís dat er niets komt. De aanroeper hoort in zijn
        // eigen timeout te lopen, en dat is wat de Berichtenbox als "onbereikbaar" registreert.
        if (gedrag.modus == GedragModus.UIT) return

        if (gedrag.modus == GedragModus.MALFORMED) {
            requestContext.abortWith(onbruikbaarAntwoord())

            return
        }

        if (uitvoering.valtOm(magazijn.oin, gedrag)) {
            requestContext.abortWith(storing(gedrag))
        }
    }

    private fun wacht(vertragingMs: Long) {
        if (vertragingMs <= 0) return

        try {
            Thread.sleep(vertragingMs)
        } catch (ex: InterruptedException) {
            // De aanroeper heeft opgehangen of de server gaat uit. De interrupt-vlag terugzetten en
            // gewoon doorgaan: het verzoek afbreken met een fout zou een storing suggereren die er
            // niet is.
            Thread.currentThread().interrupt()

            log.debugf(ex, "Wachten onderbroken na %d ms", vertragingMs)
        }
    }

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
