package nl.rijksoverheid.moz.fbs.democonsole.simulator

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/** Eén gesimuleerd magazijn zoals het beheerpad het teruggeeft. */
data class SimulatorMagazijn(val oin: String, val naam: String, val modus: String)

/** Het gedrag van één magazijn, zoals het beheerpad het aanneemt. */
data class GedragAanpassing(val oin: String, val modus: String)

data class BulkGedragVerzoek(val aanpassingen: List<GedragAanpassing>)

data class BulkGedragUitkomst(val aangepast: Int, val onbekend: List<String>)

data class SeedVerzoek(val ontvangers: List<String>, val berichtenPerMagazijn: Int, val bijlageElke: Int)

/**
 * [overgeslagen] telt de berichten die er al stonden. Vullen is herhaalbaar, en dat verschil hoort
 * in het paneel zichtbaar te zijn — anders meldt een tweede druk op de knop "gelukt" zonder dat er
 * iets veranderde.
 */
data class SeedUitkomst(
    val magazijnen: Int,
    val ontvangers: Int,
    val berichten: Int,
    val bijlagen: Int,
    val overgeslagen: Int,
    val duurMs: Long,
)

data class LeegUitkomst(val berichten: Int, val magazijnenTeruggezet: Int)

/**
 * Het beheerpad van de magazijn-simulator.
 *
 * Dit verving de WireMock-admin-API van de stub-magazijnen. Die kon alleen een magazijn helemaal aan
 * of uit zetten met een 503-overlay; de simulator kent zeven soorten gedrag en kan zijn
 * berichtenbakken vullen en legen, en dat is precies wat een demo nodig heeft.
 */
@Path("/beheer")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "magazijnsimulator")
@RegisterClientHeaders(BeheerTokenHeaders::class)
@RegisterProvider(SimulatorBeheerFout::class)
interface SimulatorBeheerClient {

    @GET
    @Path("/magazijnen")
    fun magazijnen(): List<SimulatorMagazijn>

    @PUT
    @Path("/gedrag")
    fun zetGedrag(verzoek: BulkGedragVerzoek): BulkGedragUitkomst

    @POST
    @Path("/seed")
    fun seed(verzoek: SeedVerzoek): SeedUitkomst

    @POST
    @Path("/legen")
    fun legen(): LeegUitkomst
}
