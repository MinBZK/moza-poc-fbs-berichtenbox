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
     * om dezelfde redenen als bij `POST /api/demo/bericht`, waar ze uitgeschreven staan. Het nummer
     * komt uit dezelfde ingerichte lijst als de keuzelijst van het paneel.
     */
    @POST
    fun demonstreer(@QueryParam("persona") @DefaultValue("") persona: String): OntdubbelingResultaat {
        if (persona.isBlank()) throw BadRequestException(KIES_EEN_PERSONA)

        val gekozen = personaService.alle().firstOrNull { it.id == persona }
            ?: throw onbekendePersona(persona, KIES_EEN_PERSONA)

        // Een 400 en geen 404: deze persona bestáát, hij kan dit scenario alleen niet spelen —
        // OntdubbelingService bouwt het event met een BSN-ontvanger. Het paneel biedt zo'n persona
        // niet aan, maar dit adres staat open op de origin van het paneel.
        if (gekozen.type != BSN) {
            throw BadRequestException("persona '$persona' heeft een ${gekozen.type} en geen $BSN; $KIES_EEN_PERSONA")
        }

        return service.demonstreer(gekozen.waarde)
    }

    private companion object {

        const val BSN = "BSN"

        const val KIES_EEN_PERSONA = "kies een persona met een $BSN uit personas van /api/demo/omgeving"
    }
}
