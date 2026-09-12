package nl.rijksoverheid.moz.fbs.democonsole.bereikbaarheid

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * Of de componenten van de demo zelf antwoorden, voor de chip in de toestandsbalk. Naast
 * `/api/demo/storing`, dat zegt wat Toxiproxy op de lijn ernaartoe aanzet: een component dat plat
 * ligt terwijl zijn proxy op normaal staat, zie je alleen hier.
 */
@Path("/api/demo/bereikbaarheid")
@Produces(MediaType.APPLICATION_JSON)
class BereikbaarheidResource(private val service: BereikbaarheidService) {

    @GET
    fun bereikbaarheid(): Map<String, Bereikbaarheid> = service.status()
}
