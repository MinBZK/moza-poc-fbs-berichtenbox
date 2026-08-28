package nl.rijksoverheid.moz.fbs.democonsole.simulator

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

/** De knoppen van het paneel die de gesimuleerde magazijnen aansturen. */
@Path("/api/demo/simulator")
@Produces(MediaType.APPLICATION_JSON)
class SimulatorResource(private val service: SimulatorService) {

    @GET
    @Path("/magazijnen")
    fun magazijnen(): List<SimulatorMagazijn> = service.magazijnen()

    @POST
    @Path("/actief/{aantal}")
    fun zetActief(@PathParam("aantal") aantal: Int): Map<String, Int> = try {
        service.zetActief(aantal)
    } catch (ex: IllegalArgumentException) {
        throw BadRequestException(ex.message, ex)
    }

    @POST
    @Path("/vullen")
    fun vullen(
        @QueryParam("perMagazijn") perMagazijn: Int?,
        @QueryParam("bijlageElke") bijlageElke: Int?,
    ): SeedUitkomst = service.vul(
        ontvangers = ONDERNEMERS,
        berichtenPerMagazijn = perMagazijn ?: STANDAARD_PER_MAGAZIJN,
        bijlageElke = bijlageElke ?: STANDAARD_BIJLAGE_ELKE,
    )

    @POST
    @Path("/legen")
    fun legen(): Map<String, Int> = service.herstel()

    private companion object {
        /**
         * De vier ondernemers uit `demo/genereer-magazijnen.py`, in de vorm van de
         * `X-Ontvanger`-header. Ze staan hier omdat de simulator niet weet wie er in de demo
         * meespelen — hij vult berichtenbakken, hij verzint geen ondernemers.
         */
        val ONDERNEMERS = listOf("BSN:999993653", "KVK:12345678", "KVK:90000001", "KVK:90000003")

        /**
         * Twintig is niet toevallig: de uitvraag haalt per magazijn één pagina op en het magazijn
         * levert er standaard twintig. Daarboven demonstreer je onbedoeld dát gat.
         */
        const val STANDAARD_PER_MAGAZIJN = 20
        const val STANDAARD_BIJLAGE_ELKE = 4
    }
}
