package nl.rijksoverheid.moz.fbs.democonsole.personas

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/** Eén persona: `label` in de keuzelijst, `ontvanger` als `X-Ontvanger`-header, `bron` als presentatie. */
data class PersonaDto(val id: String, val label: String, val ontvanger: String, val bron: String)

/**
 * De keuzelijst van demo-identiteiten voor een berichtenbox. Geen contract van het stelsel: dit
 * endpoint hoort bij de demo-console en staat daarom in geen enkele OpenAPI-spec.
 */
@Path("/api/demo/personas")
@Produces(MediaType.APPLICATION_JSON)
class PersonaResource(private val personaService: PersonaService) {

    @GET
    fun personas(): List<PersonaDto> = personaService.alle().map {
        PersonaDto(it.id, it.label, it.ontvanger, it.bron.wire)
    }
}
