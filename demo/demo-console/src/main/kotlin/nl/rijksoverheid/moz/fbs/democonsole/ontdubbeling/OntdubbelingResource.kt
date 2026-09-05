package nl.rijksoverheid.moz.fbs.democonsole.ontdubbeling

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import nl.rijksoverheid.moz.fbs.demopersonas.PersonaService

@Path("/api/demo/ontdubbeling")
@Produces(MediaType.APPLICATION_JSON)
class OntdubbelingResource(
    private val service: OntdubbelingService,
    private val personaService: PersonaService,
) {

    /**
     * Op de persona-`id` en niet op zijn identificatienummer: een BSN hoort niet in een URL, ook
     * niet in een demo. Een adres belandt in browsergeschiedenis, in proxylogboeken en in de
     * schermopname van een demonstratie. Het nummer blijft daarom aan deze kant en komt uit
     * dezelfde ingerichte lijst als de keuzelijst van het paneel.
     *
     * Elke bedieningsfout wordt hier afgevangen en niet met `require()`: `DemoFoutMapper` vertaalt
     * alleen een `WebApplicationException` naar zijn eigen status, dus een `require()` zou een
     * verkeerd ingevulde parameter als HTTP 500 tonen.
     *
     * Vandaar ook `@DefaultValue("")` in plaats van een vaste persona: een ontbrekende parameter
     * wordt anders `null` in een niet-nullable parameter, wat Kotlin met een `NullPointerException`
     * beantwoordt vóór de eerste regel hieronder. Een vaste default zou bovendien een
     * identificatienummer terugzetten in deze broncode.
     */
    @POST
    fun demonstreer(@QueryParam("persona") @DefaultValue("") persona: String): OntdubbelingResultaat {
        if (persona.isBlank()) throw BadRequestException(KIES_EEN_PERSONA)

        val gekozen = personaService.alle().firstOrNull { it.id == persona }
            ?: throw NotFoundException("onbekende persona '$persona'; $KIES_EEN_PERSONA")

        // Een 400 en geen 404: deze persona bestáát, hij kan dit scenario alleen niet spelen. De
        // aanmeld-webhook draagt de ontvanger als BSN, dus voor een andere identiteit valt er geen
        // event te bouwen. Het paneel biedt zo'n persona niet aan, maar dit adres staat open op de
        // origin van het paneel.
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
