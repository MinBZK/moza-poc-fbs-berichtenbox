package nl.rijksoverheid.moz.fbs.democonsole.storing

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.democonsole.HERSTELTIJD_MELDING

/**
 * De storingsknoppen die op een netwerkverbinding werken: de twee echte magazijnen, de sessiecache,
 * de profielservice, de notificatiedienst en de aanmeld-webhook.
 *
 * Voor de gesimuleerde magazijnen zijn deze knoppen er niet, en dat is geen omissie. Een proxy kan
 * een verbinding alleen traag maken of dichtzetten; de simulator kan een magazijn traag, haperend,
 * kapot, onbereikbaar of weigerend maken, en per magazijn verschillend. Die kant zit bij
 * [nl.rijksoverheid.moz.fbs.democonsole.simulator.SimulatorResource].
 *
 * A en B houden lokaal hun proxy omdat zij dragen wat de simulator niet doet — aanleveren, bijlagen,
 * de notificatie-outbox en FSC — en verschillende demo-scenario's gaan juist over het uitvallen van
 * zo'n magazijn. Op een gedeelde omgeving staan hun `TOXIPROXY_MAGAZIJN_*_URL` leeg en verbergt het
 * paneel die twee knoppen.
 */
@Path("/api/demo/storing")
@Produces(MediaType.APPLICATION_JSON)
class StoringResource(private val storingService: StoringService) {

    @GET
    fun status(): Map<String, Storingstoestand> = storingService.status()

    @POST
    @Path("/magazijn/{ab}/traag")
    fun magazijnTraag(@PathParam("ab") ab: String): Map<String, String> = traag(magazijnProxy(ab))

    @POST
    @Path("/magazijn/{ab}/uit")
    fun magazijnUit(@PathParam("ab") ab: String): Map<String, String> = uit(magazijnProxy(ab))

    @POST
    @Path("/reset")
    fun reset(): Map<String, String> {
        storingService.reset()

        // Teruglezen in plaats van "alles normaal" opschrijven. Deze knop wordt juist ingedrukt
        // wanneer er al iets niet klopt, dus een zin die niemand controleert is hier het duurst:
        // een groene bevestiging boven een stroom die nog dichtstaat, kost tijdens een demo de rest
        // van het verhaal.
        val afwijkend = storingService.status().filterValues { it != Storingstoestand.NORMAAL }

        check(afwijkend.isEmpty()) {
            "Herstel uitgevoerd, maar niet alles staat normaal: " +
                afwijkend.entries.joinToString(", ") { (proxy, toestand) -> "$proxy ${toestand.waarde}" }
        }

        return mapOf("status" to "alles normaal", "letOp" to HERSTELTIJD_MELDING)
    }

    @POST
    @Path("/{proxy}/uit")
    fun infraUit(@PathParam("proxy") proxy: String): Map<String, String> = uit(proxy)

    /**
     * Traag zetten heeft geen zichtbaar effect op een proxy die al dichtstaat: Toxiproxy neemt de
     * toxic aan met HTTP 200, maar er komt geen byte doorheen. Dat als "traag" melden vertelt het
     * publiek iets anders dan er gebeurt, dus dit is een fout — mét de handeling die hem oplost.
     */
    private fun traag(proxy: String): Map<String, String> {
        val toestand = storingService.traag(proxy, LATENCY_MS)

        check(toestand == Storingstoestand.TRAAG) {
            "$proxy staat ${toestand.waarde}, dus traag zetten verandert niets zichtbaars. " +
                "Zet hem eerst weer aan met 'Alles normaal'."
        }

        return mapOf("status" to "$proxy traag (${LATENCY_MS}ms)")
    }

    private fun uit(proxy: String): Map<String, String> {
        val toestand = storingService.uit(proxy)

        check(toestand == Storingstoestand.UIT) { "$proxy staat na het uitschakelen op ${toestand.waarde}" }

        return mapOf("status" to "$proxy uit")
    }

    private fun magazijnProxy(ab: String): String {
        if (ab != "a" && ab != "b") throw BadRequestException("magazijn moet 'a' of 'b' zijn, was: '$ab'")

        return "magazijn-$ab"
    }

    private companion object {
        const val LATENCY_MS = 6000
    }
}
