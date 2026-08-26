package nl.rijksoverheid.moz.fbs.democonsole.personas

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/** Eén persona zoals een berichtenbox hem nodig heeft: tonen, meesturen, en weten wat je ziet. */
data class PersonaDto(val id: String, val label: String, val ontvanger: String, val bron: String)

/**
 * De keuzelijst van demo-identiteiten voor een berichtenbox. Bewust niet in de OpenAPI-spec van
 * de uitvraag: het is demo-gereedschap dat vervalt zodra er echte authenticatie is.
 */
@Path("/api/demo/personas")
@Produces(MediaType.APPLICATION_JSON)
class PersonaResource(private val personaService: PersonaService) {

    @GET
    fun personas(): List<PersonaDto> = personaService.alle().map {
        PersonaDto(it.id, it.label, it.ontvanger, it.bron.name.lowercase())
    }
}
