package nl.rijksoverheid.moz.fbs.democonsole.ontdubbeling

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.democonsole.onbekendePersona
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService

@Path("/api/demo/ontdubbeling")
@Produces(MediaType.APPLICATION_JSON)
class OntdubbelingResource(
    private val service: OntdubbelingService,
    private val personaService: PersonaService,
) {

    /**
     * Op de persona-`id`, en bedieningsfouten als `WebApplicationException` met `@DefaultValue("")`:
     * om dezelfde redenen als bij `POST /api/demo/bericht`, waar ze uitgeschreven staan. De
     * ontvanger wordt hier opgezocht in dezelfde ingerichte lijst die de keuzelijst vult.
     */
    @POST
    fun demonstreer(@QueryParam("persona") @DefaultValue("") persona: String): OntdubbelingResultaat {
        if (persona.isBlank()) throw BadRequestException(KIES_EEN_PERSONA)

        val gekozen = personaService.alle().firstOrNull { it.id == persona }
            ?: throw onbekendePersona(persona, KIES_EEN_PERSONA)

        // Elk type: een berichtenbox biedt dezelfde persona's aan, en de aanmelding van de uitvraag
        // neemt elk identificatienummer aan dat de persona-inrichting toelaat.
        return service.demonstreer(Ontvanger(type = gekozen.type, waarde = gekozen.waarde))
    }

    private companion object {

        const val KIES_EEN_PERSONA = "kies een persona uit personas van /api/demo/omgeving"
    }
}
